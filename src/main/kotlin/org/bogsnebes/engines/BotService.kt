package org.bogsnebes.engines

import java.time.Clock
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger

class BotService(
    private val botUser: TelegramUser,
    private val telegramGateway: TelegramGateway,
    private val cooldownNoticeManager: CooldownNoticeManager,
    private val pingTargetRepository: PingTargetRepository,
    private val allPingSessionRepository: AllPingSessionRepository,
    private val cooldownTracker: PingCooldownTracker,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = Logger.getLogger(BotService::class.java.name)

    suspend fun handle(update: TelegramUpdate) {
        update.message?.let { handleMessage(it) }
        update.callbackQuery?.let { handleCallbackQuery(it) }
    }

    private suspend fun handleMessage(message: TelegramMessage) {
        val command = message.text?.let { CommandParser.parse(it, botUser.username, message.textSources) }

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
        val targets = pingTargetRepository.listTargets(message.chat.id)
        if (targets.isEmpty()) {
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = "Список тегов не настроен. Используйте /add @username...",
                messageThreadId = message.messageThreadId,
            )
            return
        }

        val now = Instant.now(clock)
        val previousActiveSession = allPingSessionRepository.findActiveSession(message.chat.id)
        val activeSessionCooldownRemaining = previousActiveSession
            ?.let { cooldownTracker.remainingSince(it.createdAt, now) }
        val trackedCooldownRemaining = cooldownTracker.remaining(message.chat.id, now)
        val cooldownRemaining = listOfNotNull(activeSessionCooldownRemaining, trackedCooldownRemaining).maxOrNull()
        if (cooldownRemaining != null) {
            val availableAt = cooldownTracker.reserveUntil(message.chat.id, now.plus(cooldownRemaining))
            logger.info(
                "Rejected /all for chat ${message.chat.id}: cooldown active for ${cooldownRemaining.seconds}s" +
                    ", activeSessionId=${previousActiveSession?.id}, availableAt=$availableAt"
            )
            cooldownNoticeManager.showCooldownNotice(
                chatId = message.chat.id,
                messageThreadId = message.messageThreadId,
                commandMessageId = message.messageId,
                availableAt = availableAt,
            )
            return
        }

        cooldownTracker.reserve(message.chat.id, now)

        try {
            publishAllSession(
                message = message,
                command = command,
                targets = targets,
                createdAt = now,
                previousActiveSession = previousActiveSession,
            )
        } catch (error: AllPingFormattingException) {
            cooldownTracker.clear(message.chat.id)
            logger.log(Level.WARNING, "Failed to format /all for chat ${message.chat.id}", error)
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = "Не удалось отправить /all: сократи анонс и попробуй снова.",
                messageThreadId = message.messageThreadId,
            )
        } catch (error: Throwable) {
            cooldownTracker.clear(message.chat.id)
            logger.log(Level.WARNING, "Failed to publish /all for chat ${message.chat.id}", error)
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = "Не удалось отправить /all, попробуйте еще раз.",
                messageThreadId = message.messageThreadId,
            )
        }
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

        pingTargetRepository.replaceTargets(message.chat.id, command.targets)
        telegramGateway.sendMessage(
            chatId = message.chat.id,
            text = "Список тегов обновлён: ${command.targets.joinToString(" ", transform = MentionFormatter::renderTarget)}",
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
        if (session == null) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, "Сбор уже завершен")
            return
        }
        if (session.status == AllPingSessionStatus.PENDING) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, "Сбор еще публикуется")
            return
        }
        if (session.status != AllPingSessionStatus.ACTIVE) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, "Сбор уже завершен")
            return
        }

        val username = callbackQuery.from.username?.lowercase()
        val participant = session.participants.firstOrNull { participant ->
            when {
                participant.userId != null -> participant.userId == callbackQuery.from.id
                username != null && participant.username != null -> participant.username == username
                else -> false
            }
        }
        if (participant == null) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, "Тебя нет в списке этого /all")
            return
        }

        if (participant.response == decoded.response) {
            telegramGateway.answerCallbackQuery(
                callbackQuery.id,
                "Ответ уже записан: ${decoded.response.statusText}",
            )
            return
        }

        allPingSessionRepository.updateResponse(
            sessionId = session.id,
            identityKey = participant.identityKey,
            response = decoded.response,
            respondedAt = Instant.now(clock),
        )

        val notice = if (participant.response == null) {
            "Ответ записан: ${decoded.response.statusText}"
        } else {
            "Ответ обновлен: ${decoded.response.statusText}"
        }

        val updatedSession = allPingSessionRepository.findSession(session.id)
        if (updatedSession == null) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, "Сбор уже завершен")
            return
        }

        if (refreshSessionAfterResponse(session, updatedSession, callbackQuery)) {
            telegramGateway.answerCallbackQuery(callbackQuery.id, notice)
        } else {
            telegramGateway.answerCallbackQuery(
                callbackQuery.id,
                "Ответ записан, но список временно недоступен",
            )
        }
    }

    private suspend fun publishAllSession(
        message: TelegramMessage,
        command: AllCommand,
        targets: List<PingTagTarget>,
        createdAt: Instant,
        previousActiveSession: AllPingSession?,
    ) {
        val session = allPingSessionRepository.createPendingSession(
            chatId = message.chat.id,
            messageThreadId = message.messageThreadId,
            announcement = command.announcement,
            targets = targets,
            createdAt = createdAt,
        )
        val sentMessages = mutableListOf<TelegramSentMessage>()

        try {
            val messages = AllPingFormatter.buildMessageChunks(session)
            val keyboard = AllPingFormatter.buildKeyboard(session.id)

            messages.forEachIndexed { index, payload ->
                val sentMessage = telegramGateway.sendMessage(
                    chatId = message.chat.id,
                    text = payload,
                    messageThreadId = message.messageThreadId,
                    inlineKeyboard = if (index == 0) keyboard else null,
                )
                sentMessages += sentMessage
                allPingSessionRepository.saveMessages(session.id, sentMessages)
            }

            check(allPingSessionRepository.activateSession(session.id)) {
                "Failed to activate pending session ${session.id}"
            }
            previousActiveSession?.let { closeSession(it, createdAt) }

            logger.info(
                "Sent interactive /all for ${targets.size} ping tags across ${messages.size} messages for chat ${message.chat.id}"
            )
        } catch (error: Throwable) {
            cleanupPendingSession(session, sentMessages, createdAt)
            throw error
        }
    }

    private suspend fun cleanupPendingSession(
        session: AllPingSession,
        sentMessages: List<TelegramSentMessage>,
        closedAt: Instant,
    ) {
        sentMessages.forEach { sentMessage ->
            deleteMessageIgnoringErrors(
                chatId = sentMessage.chatId,
                messageId = sentMessage.messageId,
                description = "partially published /all message",
            )
        }
        allPingSessionRepository.closeSession(session.id, closedAt)
    }

    private suspend fun refreshSessionAfterResponse(
        previousSession: AllPingSession,
        session: AllPingSession,
        callbackQuery: TelegramCallbackQuery,
    ): Boolean {
        return try {
            editActiveSession(previousSession, session)
            syncCallbackSourceMessage(session, callbackQuery)
            true
        } catch (error: Throwable) {
            logger.log(
                Level.WARNING,
                "Failed to refresh session ${session.id} after callback ${callbackQuery.id}",
                error,
            )
            closeSession(session, Instant.now(clock))
            false
        }
    }

    private suspend fun closeSession(session: AllPingSession, closedAt: Instant) {
        val firstMessage = session.messages.minByOrNull(AllPingSessionMessage::chunkIndex)
        if (firstMessage != null) {
            runCatching {
                telegramGateway.removeInlineKeyboard(chatId = session.chatId, messageId = firstMessage.messageId)
            }.onFailure { error ->
                logger.log(
                    Level.WARNING,
                    "Failed to remove inline keyboard for session ${session.id}",
                    error,
                )
            }
        }
        allPingSessionRepository.closeSession(session.id, closedAt)
    }

    private suspend fun editActiveSession(previousSession: AllPingSession, session: AllPingSession) {
        check(session.status == AllPingSessionStatus.ACTIVE) {
            "Cannot edit non-active session ${session.id}"
        }
        check(previousSession.id == session.id) {
            "Cannot diff sessions ${previousSession.id} and ${session.id}"
        }

        val previousMessages = AllPingFormatter.buildMessageChunks(previousSession)
        val messages = AllPingFormatter.buildMessageChunks(session)
        check(previousMessages.size == messages.size) {
            "Session ${session.id} rendered chunk count changed from ${previousMessages.size} to ${messages.size}"
        }
        check(session.messages.size == messages.size) {
            "Session ${session.id} has ${session.messages.size} saved messages but ${messages.size} rendered chunks"
        }

        val keyboard = AllPingFormatter.buildKeyboard(session.id)
        session.messages
            .sortedBy(AllPingSessionMessage::chunkIndex)
            .forEachIndexed { index, sentMessage ->
                if (previousMessages[index] == messages[index]) {
                    return@forEachIndexed
                }

                telegramGateway.editMessageText(
                    chatId = session.chatId,
                    messageId = sentMessage.messageId,
                    text = messages[index],
                    inlineKeyboard = if (index == 0) keyboard else null,
                )
            }
    }

    private suspend fun syncCallbackSourceMessage(session: AllPingSession, callbackQuery: TelegramCallbackQuery) {
        val firstSavedMessage = session.messages.minByOrNull(AllPingSessionMessage::chunkIndex) ?: return
        if (callbackQuery.chat.id != session.chatId || callbackQuery.messageId == firstSavedMessage.messageId) {
            return
        }

        val firstChunkText = AllPingFormatter.buildMessageChunks(session).firstOrNull() ?: return
        try {
            telegramGateway.editMessageText(
                chatId = callbackQuery.chat.id,
                messageId = callbackQuery.messageId,
                text = firstChunkText,
                inlineKeyboard = AllPingFormatter.buildKeyboard(session.id),
            )
        } catch (error: Throwable) {
            logger.warning(
                "Failed to sync callback source message ${callbackQuery.messageId} for session ${session.id}: ${error.message}"
            )
        }
    }

    private suspend fun deleteMessageIgnoringErrors(chatId: Long, messageId: Long, description: String) {
        runCatching {
            telegramGateway.deleteMessage(chatId = chatId, messageId = messageId)
        }.onFailure { error ->
            logger.log(
                Level.WARNING,
                "Failed to delete $description $messageId in chat $chatId",
                error,
            )
        }
    }
}
