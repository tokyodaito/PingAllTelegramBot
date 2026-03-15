package org.bogsnebes.engines

import dev.inmo.tgbotapi.bot.RequestsExecutor
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.types.update.abstracts.Update
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val libraryJson = Json {
    ignoreUnknownKeys = true
}

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

fun decodeLibraryUpdate(payload: String): Update = libraryJson.decodeFromString(
    UpdateDeserializationStrategy,
    payload.trimIndent(),
)

class RecordingRequestsExecutor(
    private val responses: Map<String, Any>,
) : RequestsExecutor {
    val requests = mutableListOf<Request<*>>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> execute(request: Request<T>): T {
        requests += request
        return responses[request.method()] as? T
            ?: error("No stubbed response for ${request.method()}")
    }

    override fun close() = Unit
}
