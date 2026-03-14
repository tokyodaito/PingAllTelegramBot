package org.bogsnebes.engines

class FakeTelegramGateway : TelegramGateway {
    val sentMessages = mutableListOf<SentMessage>()

    override suspend fun sendMessage(chatId: Long, text: String, messageThreadId: Long?) {
        sentMessages += SentMessage(chatId, text, messageThreadId)
    }
}

data class SentMessage(
    val chatId: Long,
    val text: String,
    val messageThreadId: Long?,
)
