package org.bogsnebes.engines

import dev.inmo.tgbotapi.bot.RequestsExecutor
import dev.inmo.tgbotapi.requests.abstracts.Request
import dev.inmo.tgbotapi.types.chat.member.ChatMember
import dev.inmo.tgbotapi.types.chat.member.ChatMemberSerializer
import dev.inmo.tgbotapi.types.update.abstracts.Update
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque

private val libraryJson = Json {
    ignoreUnknownKeys = true
}

class FakeTelegramGateway : TelegramGateway {
    val sentMessages = mutableListOf<SentMessage>()
    val editAttempts = mutableListOf<EditedMessage>()
    val editedMessages = mutableListOf<EditedMessage>()
    val deletedMessages = mutableListOf<DeletedMessage>()
    val callbackAnswers = mutableListOf<CallbackAnswer>()
    val removedInlineKeyboards = mutableListOf<RemovedInlineKeyboard>()
    val adminUsers = mutableSetOf<Pair<Long, Long>>()
    val editFailures = ArrayDeque<Throwable>()
    val sendFailures = mutableMapOf<Int, Throwable>()
    private var nextMessageId = 1L
    private var sendCallCount = 0

    override suspend fun sendMessage(
        chatId: Long,
        text: String,
        messageThreadId: Long?,
        inlineKeyboard: TelegramInlineKeyboard?,
    ): TelegramSentMessage {
        sendCallCount += 1
        sendFailures.remove(sendCallCount)?.let { throw it }

        val sentMessage = SentMessage(
            chatId = chatId,
            messageId = nextMessageId++,
            text = text,
            messageThreadId = messageThreadId,
            inlineKeyboard = inlineKeyboard,
        )
        sentMessages += sentMessage
        return TelegramSentMessage(
            chatId = sentMessage.chatId,
            messageId = sentMessage.messageId,
            messageThreadId = sentMessage.messageThreadId,
        )
    }

    override suspend fun editMessageText(
        chatId: Long,
        messageId: Long,
        text: String,
        inlineKeyboard: TelegramInlineKeyboard?,
    ) {
        val editedMessage = EditedMessage(chatId, messageId, text, inlineKeyboard)
        editAttempts += editedMessage
        if (editFailures.isNotEmpty()) {
            throw editFailures.removeFirst()
        }
        editedMessages += editedMessage
    }

    override suspend fun removeInlineKeyboard(chatId: Long, messageId: Long) {
        removedInlineKeyboards += RemovedInlineKeyboard(chatId, messageId)
    }

    override suspend fun deleteMessage(chatId: Long, messageId: Long) {
        deletedMessages += DeletedMessage(chatId, messageId)
    }

    override suspend fun answerCallbackQuery(callbackQueryId: String, text: String) {
        callbackAnswers += CallbackAnswer(callbackQueryId, text)
    }

    override suspend fun isChatAdmin(chatId: Long, userId: Long): Boolean = (chatId to userId) in adminUsers
}

data class SentMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val messageThreadId: Long?,
    val inlineKeyboard: TelegramInlineKeyboard?,
)

data class EditedMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val inlineKeyboard: TelegramInlineKeyboard?,
)

data class DeletedMessage(
    val chatId: Long,
    val messageId: Long,
)

data class RemovedInlineKeyboard(
    val chatId: Long,
    val messageId: Long,
)

data class CallbackAnswer(
    val callbackQueryId: String,
    val text: String,
)

fun rateLimitException(seconds: Long): TelegramRateLimitException = TelegramRateLimitException(
    retryAfter = Duration.ofSeconds(seconds),
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

fun decodeChatMember(payload: String): ChatMember = libraryJson.decodeFromString(
    ChatMemberSerializer,
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
