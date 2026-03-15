package org.bogsnebes.engines

data class TelegramUpdate(
    val updateId: Long,
    val message: TelegramMessage? = null,
    val chatMember: TelegramChatMemberUpdated? = null,
    val callbackQuery: TelegramCallbackQuery? = null,
)

data class TelegramMessage(
    val messageId: Long,
    val messageThreadId: Long? = null,
    val date: Long,
    val chat: TelegramChat,
    val from: TelegramUser? = null,
    val text: String? = null,
    val newChatMembers: List<TelegramUser> = emptyList(),
)

data class TelegramSentMessage(
    val chatId: Long,
    val messageId: Long,
    val messageThreadId: Long? = null,
)

data class TelegramCallbackQuery(
    val id: String,
    val from: TelegramUser,
    val data: String,
    val chat: TelegramChat,
    val messageId: Long,
    val messageThreadId: Long? = null,
)

data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String? = null,
)

data class TelegramUser(
    val id: Long,
    val isBot: Boolean,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = null,
)

data class TelegramChatMemberUpdated(
    val chat: TelegramChat,
    val date: Long,
    val oldChatMember: TelegramChatMember,
    val newChatMember: TelegramChatMember,
)

data class TelegramChatMember(
    val user: TelegramUser,
    val status: String,
)

data class TelegramInlineKeyboard(
    val rows: List<List<TelegramInlineButton>>,
)

data class TelegramInlineButton(
    val text: String,
    val callbackData: String,
)
