package org.bogsnebes.engines

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.message.HTML
import dev.inmo.tgbotapi.types.toChatId

interface TelegramGateway {
    suspend fun sendMessage(chatId: Long, text: String, messageThreadId: Long? = null)
}

class TgBotApiGateway(
    private val bot: TelegramBot,
) : TelegramGateway {
    override suspend fun sendMessage(chatId: Long, text: String, messageThreadId: Long?) {
        bot.sendMessage(
            chatId = chatId.toChatId(),
            text = text,
            parseMode = HTML,
            linkPreviewOptions = LinkPreviewOptions.Disabled,
            threadId = messageThreadId?.let(::MessageThreadId),
        )
    }
}
