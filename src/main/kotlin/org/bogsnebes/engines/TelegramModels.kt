package org.bogsnebes.engines

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
)

@Serializable
data object EmptyTelegramRequest

@Serializable
data class GetUpdatesRequest(
    val offset: Long? = null,
    val timeout: Int,
    @SerialName("allowed_updates")
    val allowedUpdates: List<String>,
)

@Serializable
data class SendMessageRequest(
    @SerialName("chat_id")
    val chatId: Long,
    val text: String,
    @SerialName("parse_mode")
    val parseMode: String = "HTML",
    @SerialName("message_thread_id")
    val messageThreadId: Long? = null,
    @SerialName("disable_web_page_preview")
    val disableWebPagePreview: Boolean = true,
)

@Serializable
data class TelegramUpdate(
    @SerialName("update_id")
    val updateId: Long,
    val message: TelegramMessage? = null,
    @SerialName("chat_member")
    val chatMember: TelegramChatMemberUpdated? = null,
)

@Serializable
data class TelegramMessage(
    @SerialName("message_id")
    val messageId: Long,
    @SerialName("message_thread_id")
    val messageThreadId: Long? = null,
    val date: Long,
    val chat: TelegramChat,
    val from: TelegramUser? = null,
    val text: String? = null,
    @SerialName("new_chat_members")
    val newChatMembers: List<TelegramUser> = emptyList(),
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String? = null,
)

@Serializable
data class TelegramUser(
    val id: Long,
    @SerialName("is_bot")
    val isBot: Boolean,
    @SerialName("first_name")
    val firstName: String,
    @SerialName("last_name")
    val lastName: String? = null,
    val username: String? = null,
)

@Serializable
data class TelegramChatMemberUpdated(
    val chat: TelegramChat,
    val date: Long,
    @SerialName("old_chat_member")
    val oldChatMember: TelegramChatMember,
    @SerialName("new_chat_member")
    val newChatMember: TelegramChatMember,
)

@Serializable
data class TelegramChatMember(
    val user: TelegramUser,
    val status: String,
)
