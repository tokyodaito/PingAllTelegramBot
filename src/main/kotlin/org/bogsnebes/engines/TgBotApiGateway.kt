package org.bogsnebes.engines

import dev.inmo.tgbotapi.bot.RequestsExecutor
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.chat.members.getChatMember
import dev.inmo.tgbotapi.extensions.api.deleteMessage
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.edit.text.editMessageText
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.MessageThreadId
import dev.inmo.tgbotapi.types.CallbackQueryId
import dev.inmo.tgbotapi.types.asTelegramMessageId
import dev.inmo.tgbotapi.types.chat.member.AdministratorChatMember
import dev.inmo.tgbotapi.types.chat.member.OwnerChatMember
import dev.inmo.tgbotapi.types.message.HTML
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.buttons.inline.dataInlineButton
import dev.inmo.tgbotapi.types.toChatId

interface TelegramGateway {
    suspend fun sendMessage(
        chatId: Long,
        text: String,
        messageThreadId: Long? = null,
        inlineKeyboard: TelegramInlineKeyboard? = null,
    ): TelegramSentMessage

    suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        inlineKeyboard: TelegramInlineKeyboard? = null,
    )

    suspend fun removeInlineKeyboard(chatId: Long, messageId: Long)
    suspend fun deleteMessage(chatId: Long, messageId: Long)
    suspend fun answerCallbackQuery(callbackQueryId: String, text: String)
    suspend fun isChatAdmin(chatId: Long, userId: Long): Boolean
}

class TgBotApiGateway(
    private val bot: RequestsExecutor,
) : TelegramGateway {
    override suspend fun sendMessage(
        chatId: Long,
        text: String,
        messageThreadId: Long?,
        inlineKeyboard: TelegramInlineKeyboard?,
    ): TelegramSentMessage {
        val message = bot.sendMessage(
            chatId = chatId.toChatId(),
            text = text,
            parseMode = HTML,
            linkPreviewOptions = LinkPreviewOptions.Disabled,
            threadId = messageThreadId?.let(::MessageThreadId),
            replyMarkup = inlineKeyboard?.toLibraryMarkup(),
        )

        return TelegramSentMessage(
            chatId = chatId,
            messageId = message.messageId.long,
            messageThreadId = messageThreadId,
        )
    }

    override suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        inlineKeyboard: TelegramInlineKeyboard?,
    ) {
        bot.editMessageText(
            chatId = chatId.toChatId(),
            messageId = messageId.asTelegramMessageId(),
            text = text,
            parseMode = HTML,
            linkPreviewOptions = LinkPreviewOptions.Disabled,
            replyMarkup = inlineKeyboard?.toLibraryMarkup(),
        )
    }

    override suspend fun removeInlineKeyboard(chatId: Long, messageId: Long) {
        bot.editMessageReplyMarkup(
            chatId = chatId.toChatId(),
            messageId = messageId.asTelegramMessageId(),
        )
    }

    override suspend fun deleteMessage(chatId: Long, messageId: Long) {
        bot.deleteMessage(
            chatId = chatId.toChatId(),
            messageId = messageId.asTelegramMessageId(),
        )
    }

    override suspend fun answerCallbackQuery(callbackQueryId: String, text: String) {
        bot.answerCallbackQuery(
            callbackQueryId = CallbackQueryId(callbackQueryId),
            text = text,
        )
    }

    override suspend fun isChatAdmin(chatId: Long, userId: Long): Boolean {
        return when (bot.getChatMember(chatId = chatId.toChatId(), userId = userId.toChatId())) {
            is OwnerChatMember, is AdministratorChatMember -> true
            else -> false
        }
    }
}

private fun TelegramInlineKeyboard.toLibraryMarkup(): InlineKeyboardMarkup = InlineKeyboardMarkup(
    rows.map { row ->
        row.map { button ->
            dataInlineButton(
                text = button.text,
                data = button.callbackData,
            )
        }
    }
)
