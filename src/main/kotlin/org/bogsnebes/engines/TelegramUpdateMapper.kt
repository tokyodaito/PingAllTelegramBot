package org.bogsnebes.engines

import dev.inmo.tgbotapi.types.chat.Bot
import dev.inmo.tgbotapi.types.chat.PreviewBot
import dev.inmo.tgbotapi.types.chat.PreviewBusinessChat
import dev.inmo.tgbotapi.types.chat.PreviewChannelChat
import dev.inmo.tgbotapi.types.chat.PreviewChat
import dev.inmo.tgbotapi.types.chat.PreviewGroupChat
import dev.inmo.tgbotapi.types.chat.PreviewPrivateChat
import dev.inmo.tgbotapi.types.chat.PreviewPublicChat
import dev.inmo.tgbotapi.types.chat.PreviewSupergroupChat
import dev.inmo.tgbotapi.types.chat.User
import dev.inmo.tgbotapi.types.chat.member.ChatMember
import dev.inmo.tgbotapi.types.chat.member.ChatMemberUpdated
import dev.inmo.tgbotapi.types.message.ChatEvents.NewChatMembers
import dev.inmo.tgbotapi.types.message.abstracts.AccessibleMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.GroupEventMessage
import dev.inmo.tgbotapi.types.message.abstracts.OptionallyFromUserMessage
import dev.inmo.tgbotapi.types.message.abstracts.PossiblyTopicMessage
import dev.inmo.tgbotapi.types.message.content.TextedContent
import dev.inmo.tgbotapi.types.update.abstracts.BaseMessageUpdate
import dev.inmo.tgbotapi.types.update.abstracts.ChatMemberUpdatedUpdate
import dev.inmo.tgbotapi.types.update.abstracts.Update

object TelegramUpdateMapper {
    fun map(update: Update): TelegramUpdate? = when (update) {
        is BaseMessageUpdate -> TelegramUpdate(
            updateId = update.updateId.long,
            message = mapMessage(update.data),
        )

        is ChatMemberUpdatedUpdate -> TelegramUpdate(
            updateId = update.updateId.long,
            chatMember = mapChatMemberUpdated(update.data),
        )

        else -> null
    }

    private fun mapMessage(message: AccessibleMessage): TelegramMessage {
        val content = (message as? ContentMessage<*>)?.content
        val newChatMembers = (message as? GroupEventMessage<*>)?.chatEvent
            ?.let { event ->
                if (event is NewChatMembers) {
                    event.members.map(User::toTelegramUser)
                } else {
                    emptyList()
                }
            }
            ?: emptyList()

        return TelegramMessage(
            messageId = message.messageId.long,
            messageThreadId = (message as? PossiblyTopicMessage)?.threadId?.long
                ?: message.metaInfo.threadId?.long,
            date = message.date.unixMillisLong / 1_000L,
            chat = message.chat.toTelegramChat(),
            from = (message as? OptionallyFromUserMessage)?.from?.toTelegramUser(),
            text = (content as? TextedContent)?.text,
            newChatMembers = newChatMembers,
        )
    }

    private fun mapChatMemberUpdated(update: ChatMemberUpdated): TelegramChatMemberUpdated = TelegramChatMemberUpdated(
        chat = update.chat.toTelegramChat(),
        date = update.date.asDate.unixMillisLong / 1_000L,
        oldChatMember = update.oldChatMemberState.toTelegramChatMember(),
        newChatMember = update.newChatMemberState.toTelegramChatMember(),
    )
}

internal fun User.toTelegramUser(): TelegramUser = TelegramUser(
    id = id.chatId.long,
    isBot = this is Bot || this is PreviewBot,
    firstName = firstName,
    lastName = lastName.takeIf { it.isNotBlank() },
    username = username?.withoutAt,
)

private fun PreviewChat.toTelegramChat(): TelegramChat = TelegramChat(
    id = id.chatId.long,
    type = when (this) {
        is PreviewSupergroupChat -> "supergroup"
        is PreviewGroupChat -> "group"
        is PreviewChannelChat -> "channel"
        is PreviewPrivateChat, is PreviewBusinessChat -> "private"
        else -> "private"
    },
    title = (this as? PreviewPublicChat)?.title,
)

private fun ChatMember.toTelegramChatMember(): TelegramChatMember = TelegramChatMember(
    user = user.toTelegramUser(),
    status = status.status,
)
