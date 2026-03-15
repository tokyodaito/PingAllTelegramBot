package org.bogsnebes.engines

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.logging.Logger

class BotService(
    private val botUser: TelegramUser,
    private val telegramGateway: TelegramGateway,
    private val cooldownNoticeManager: CooldownNoticeManager,
    private val memberRepository: MemberRepository,
    private val allPingSessionRepository: AllPingSessionRepository,
    private val cooldownTracker: PingCooldownTracker,
    private val activeWindow: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = Logger.getLogger(BotService::class.java.name)

    suspend fun handle(update: TelegramUpdate) {
        update.message?.let { handleMessage(it) }
        update.chatMember?.let { handleChatMemberUpdate(it) }
        update.callbackQuery?.let { handleCallbackQuery(it) }
    }

    private suspend fun handleMessage(message: TelegramMessage) {
        val command = message.text?.let { CommandParser.parse(it, botUser.username) }

        if (!message.chat.isGroupLike()) {
            when (command) {
                is AllCommand -> telegramGateway.sendMessage(
                    chatId = message.chat.id,
                    text = "Команда /all работает только в группах и супергруппах.",
                    messageThreadId = message.messageThreadId,
                )

                is AddCommand, InvalidAddCommand -> telegramGateway.sendMessage(
                    chatId = message.chat.id,
                    text = "Команда /add работает только в группах и супергруппах.",
                    messageThreadId = message.messageThreadId,
                )

                null -> Unit
            }
            return
        }

        val seenAt = Instant.ofEpochSecond(message.date)
        message.from?.let {
            memberRepository.upsertSeenMember(
                chatId = message.chat.id,
                user = it,
                status = "member",
                seenAt = seenAt,
                source = "message",
            )
        }
        message.newChatMembers.forEach { user ->
            memberRepository.upsertSeenMember(
                chatId = message.chat.id,
                user = user,
                status = "member",
                seenAt = seenAt,
                source = "new_chat_members",
            )
        }

        val sender = message.from ?: return
        if (command == null || sender.isBot) {
            return
        }

        when (command) {
            is AllCommand -> handleAllCommand(message, command)
            is AddCommand -> handleAddCommand(message, sender.id, command)
            InvalidAddCommand -> handleInvalidAddCommand(message, sender.id)
        }
    }

    private suspend fun handleAllCommand(message: TelegramMessage, command: AllCommand) {
        val usernames = memberRepository.listPingTags(message.chat.id)
        if (usernames.isEmpty()) {
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = "Список тегов не настроен. Используйте /add @username...",
                messageThreadId = message.messageThreadId,
            )
            return
        }

        val now = Instant.now(clock)
        val cooldownRemaining = cooldownTracker.remainingOrReserve(message.chat.id, now)
        if (cooldownRemaining != null) {
            cooldownNoticeManager.showCooldownNotice(
                chatId = message.chat.id,
                messageThreadId = message.messageThreadId,
                commandMessageId = message.messageId,
                availableAt = now.plus(cooldownRemaining),
            )
            return
        }

        closeActiveSession(chatId = message.chat.id, closedAt = now)

        val session = allPingSessionRepository.createSession(
            chatId = message.chat.id,
            messageThreadId = message.messageThreadId,
            announcement = command.announcement,
            usernames = usernames,
            createdAt = now,
        )
        val messages = AllPingFormatter.buildMessageChunks(session)
        val keyboard = AllPingFormatter.buildKeyboard(session.id)
        val sentMessages = messages.mapIndexed { index, payload ->
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = payload,
                messageThreadId = message.messageThreadId,
                inlineKeyboard = if (index == 0) keyboard else null,
            )
        }
        allPingSessionRepository.saveMessages(session.id, sentMessages)

        logger.info(
            "Sent interactive /all for ${usernames.size} ping tags across ${messages.size} messages for chat ${message.chat.id}"
        )
    }

    private suspend fun handleAddCommand(message: TelegramMessage, senderId: Long, command: AddCommand) {
        if (!telegramGateway.isChatAdmin(message.chat.id, senderId)) {
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = "Команда /add доступна только администраторам чата.",
                messageThreadId = message.messageThreadId,
            )
            return
        }

        memberRepository.replacePingTags(message.chat.id, command.usernames)
        telegramGateway.sendMessage(
            chatId = message.chat.id,
            text = "Список тегов обновлён: ${command.usernames.joinToString(" ") { "@$it" }}",
            messageThreadId = message.messageThreadId,
        )
    }

    private suspend fun handleInvalidAddCommand(message: TelegramMessage, senderId: Long) {
        if (!telegramGateway.isChatAdmin(message.chat.id, senderId)) {
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = "Команда /add доступна только администраторам чата.",
                messageThreadId = message.messageThreadId,
            )
            return
        }

        telegramGateway.sendMessage(
            chatId = message.chat.id,
            text = "Использование: /add @username1 @username2",
            messageThreadId = message.messageThreadId,
        )
    }

    private suspend fun handleCallbackQuery(callbackQuery: TelegramCallbackQuery) {
        val decoded = AllPingCallbackData.decode(callbackQuery.data) ?: return
        val session = allPingSessionRepository.findSession(decoded.sessionId)
        if (session == null || session.status != AllPingSessionStatus.ACTIVE) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, "Сбор уже завершен")
            return
        }

        val username = callbackQuery.from.username?.lowercase()
        if (username == null) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, "Тебя нет в списке этого /all")
            return
        }

        val participant = session.participants.firstOrNull { it.username == username }
        if (participant == null) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, "Тебя нет в списке этого /all")
            return
        }

        allPingSessionRepository.updateResponse(
            sessionId = session.id,
            username = username,
            response = decoded.response,
            respondedAt = Instant.now(clock),
        )

        val updatedSession = allPingSessionRepository.findSession(session.id)
            ?: error("All ping session ${session.id} disappeared after response update")
        editActiveSession(updatedSession)

        val notice = if (participant.response == null) {
            "Ответ записан: ${decoded.response.statusText}"
        } else {
            "Ответ обновлен: ${decoded.response.statusText}"
        }
        telegramGateway.answerCallbackQuery(callbackQuery.id, notice)
    }

    private fun handleChatMemberUpdate(update: TelegramChatMemberUpdated) {
        if (!update.chat.isGroupLike()) {
            return
        }

        memberRepository.upsertSeenMember(
            chatId = update.chat.id,
            user = update.newChatMember.user,
            status = update.newChatMember.status,
            seenAt = Instant.ofEpochSecond(update.date),
            source = "chat_member",
        )
    }

    private suspend fun closeActiveSession(chatId: Long, closedAt: Instant) {
        val activeSession = allPingSessionRepository.findActiveSession(chatId) ?: return
        val firstMessage = activeSession.messages.minByOrNull(AllPingSessionMessage::chunkIndex)
        if (firstMessage != null) {
            telegramGateway.removeInlineKeyboard(chatId = activeSession.chatId, messageId = firstMessage.messageId)
        }
        allPingSessionRepository.closeSession(activeSession.id, closedAt)
    }

    private suspend fun editActiveSession(session: AllPingSession) {
        val messages = AllPingFormatter.buildMessageChunks(session)
        val keyboard = AllPingFormatter.buildKeyboard(session.id)

        session.messages
            .sortedBy(AllPingSessionMessage::chunkIndex)
            .forEachIndexed { index, sentMessage ->
                telegramGateway.editMessageText(
                    chatId = session.chatId,
                    messageId = sentMessage.messageId,
                    text = messages[index],
                    inlineKeyboard = if (index == 0) keyboard else null,
                )
            }
    }
}
