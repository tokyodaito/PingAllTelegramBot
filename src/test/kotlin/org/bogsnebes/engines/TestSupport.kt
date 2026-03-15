package org.bogsnebes.engines

import dev.inmo.tgbotapi.bot.RequestsExecutor
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.types.update.abstracts.Update
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val libraryJson = Json {
    ignoreUnknownKeys = true
}

class FakeTelegramGateway : TelegramGateway {
    val sentMessages = mutableListOf<SentMessage>()
    val editedMessages = mutableListOf<EditedMessage>()
    val deletedMessages = mutableListOf<DeletedMessage>()
    private var nextMessageId = 1L

    override suspend fun sendMessage(chatId: Long, text: String, messageThreadId: Long?): TelegramSentMessage {
        val sentMessage = SentMessage(
            chatId = chatId,
            messageId = nextMessageId++,
            text = text,
            messageThreadId = messageThreadId,
        )
        sentMessages += sentMessage
        return TelegramSentMessage(
            chatId = sentMessage.chatId,
            messageId = sentMessage.messageId,
            messageThreadId = sentMessage.messageThreadId,
        )
    }

    override suspend fun editMessageText(chatId: Long, messageId: Long, text: String) {
        editedMessages += EditedMessage(chatId, messageId, text)
    }

    override suspend fun deleteMessage(chatId: Long, messageId: Long) {
        deletedMessages += DeletedMessage(chatId, messageId)
    }
}

data class SentMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val messageThreadId: Long?,
)

data class EditedMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String,
)

data class DeletedMessage(
    val chatId: Long,
    val messageId: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulerClock(
    private val scheduler: TestCoroutineScheduler,
    private val initialInstant: Instant,
    private val zoneId: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun withZone(zone: ZoneId): Clock = SchedulerClock(
        scheduler = scheduler,
        initialInstant = initialInstant,
        zoneId = zone,
    )

    override fun getZone(): ZoneId = zoneId

    override fun instant(): Instant = initialInstant.plusMillis(scheduler.currentTime)
}

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
