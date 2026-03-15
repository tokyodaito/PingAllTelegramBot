package org.bogsnebes.engines

import dev.inmo.tgbotapi.bot.RequestsExecutor
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.asTelegramMessageId
import dev.inmo.tgbotapi.types.message.HTML
import dev.inmo.tgbotapi.types.toChatId

interface TelegramGateway {
    suspend fun sendMessage(chatId: Long, text: String, messageThreadId: Long? = null): TelegramSentMessage
    suspend fun editMessageText(chatId: Long, messageId: Long, text: String)
    suspend fun deleteMessage(chatId: Long, messageId: Long)
}

class TgBotApiGateway(
    private val bot: RequestsExecutor,
) : TelegramGateway {
    override suspend fun sendMessage(chatId: Long, text: String, messageThreadId: Long?): TelegramSentMessage {
        val message = bot.sendMessage(
            chatId = chatId.toChatId(),
            text = text,
            parseMode = HTML,
            linkPreviewOptions = LinkPreviewOptions.Disabled,
            threadId = messageThreadId?.let(::MessageThreadId),
        )

        return TelegramSentMessage(
            chatId = chatId,
            messageId = message.messageId.long,
            messageThreadId = messageThreadId,
        )
    }

    override suspend fun editMessageText(chatId: Long, messageId: Long, text: String) {
        bot.editMessageText(
            chatId = chatId.toChatId(),
            messageId = messageId.asTelegramMessageId(),
            text = text,
            parseMode = HTML,
            linkPreviewOptions = LinkPreviewOptions.Disabled,
        )
    }

    override suspend fun deleteMessage(chatId: Long, messageId: Long) {
        bot.deleteMessage(
            chatId = chatId.toChatId(),
            messageId = messageId.asTelegramMessageId(),
        )
    }
}
