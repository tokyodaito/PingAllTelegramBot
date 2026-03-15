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
    private val cooldownTracker: PingCooldownTracker,
    private val activeWindow: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = Logger.getLogger(BotService::class.java.name)

    suspend fun handle(update: TelegramUpdate) {
        update.message?.let { handleMessage(it) }
        update.chatMember?.let { handleChatMemberUpdate(it) }
    }

    private suspend fun handleMessage(message: TelegramMessage) {
        val command = message.text?.let { CommandParser.parseAllCommand(it, botUser.username) }

        if (!message.chat.isGroupLike()) {
            if (command != null) {
                telegramGateway.sendMessage(
                    chatId = message.chat.id,
                    text = "Команда /all работает только в группах и супергруппах.",
                    messageThreadId = message.messageThreadId,
                )
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

        if (command == null || message.from == null || message.from.isBot) {
            return
        }

        val eligibleMembers = memberRepository.listMentionableMembers(
            chatId = message.chat.id,
            activeSince = Instant.now(clock).minus(activeWindow),
        ).filterNot { it.userId == botUser.id }

        if (eligibleMembers.isEmpty()) {
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = "Бот пока не знает активных участников этого чата за последние 7 дней.",
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

        val messages = MentionFormatter.buildMessages(eligibleMembers, command.announcement)
        messages.forEach { payload ->
            telegramGateway.sendMessage(
                chatId = message.chat.id,
                text = payload,
                messageThreadId = message.messageThreadId,
            )
        }

        logger.info(
            "Sent ${eligibleMembers.size} mentions across ${messages.size} messages for chat ${message.chat.id}"
        )
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
}
