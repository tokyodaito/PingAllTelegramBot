package org.bogsnebes.engines

import dev.inmo.tgbotapi.types.Response
import dev.inmo.tgbotapi.types.update.abstracts.Update
import dev.inmo.tgbotapi.types.update.abstracts.UpdateDeserializationStrategy
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.util.logging.Logger

private const val TELEGRAM_API_BASE_URL = "https://api.telegram.org"
private const val DEFAULT_POLL_RETRY_DELAY_MILLIS = 5_000L

internal fun interface TelegramUpdatesFetcher {
    suspend fun fetch(offset: Long?): List<Update>
}

internal class TelegramGetUpdatesClient(
    private val requestJson: suspend (String) -> String,
    private val pollTimeoutSeconds: Int,
    private val allowedUpdates: List<String>,
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) : TelegramUpdatesFetcher {
    override suspend fun fetch(offset: Long?): List<Update> {
        val responseBody = requestJson(buildRequestBody(offset))
        val response = json.decodeFromString(Response.serializer(), responseBody)

        if (!response.ok) {
            throw TelegramPollingException(
                errorCode = response.errorCode,
                description = response.description,
            )
        }

        val result = response.result
            ?: throw TelegramPollingException(
                errorCode = response.errorCode,
                description = "Telegram returned no result for getUpdates",
            )

        return result.jsonArray.map { json.decodeFromJsonElement(UpdateDeserializationStrategy, it) }
    }

    private fun buildRequestBody(offset: Long?): String = buildJsonObject {
        offset?.let { put("offset", it) }
        put("limit", 100)
        put("timeout", pollTimeoutSeconds)
        put("allowed_updates", buildJsonArray {
            allowedUpdates.forEach { add(JsonPrimitive(it)) }
        })
    }.toString()

    companion object {
        fun fromHttpClient(
            client: HttpClient,
            botToken: String,
            pollTimeoutSeconds: Int,
            allowedUpdates: List<String>,
        ): TelegramGetUpdatesClient = TelegramGetUpdatesClient(
            requestJson = { requestBody ->
                client.post("$TELEGRAM_API_BASE_URL/bot$botToken/getUpdates") {
                    setBody(TextContent(requestBody, ContentType.Application.Json))
                }.bodyAsText()
            },
            pollTimeoutSeconds = pollTimeoutSeconds,
            allowedUpdates = allowedUpdates,
        )
    }
}

internal class TelegramLongPollingRunner(
    private val updatesFetcher: TelegramUpdatesFetcher,
    private val retryDelayMillis: Long = DEFAULT_POLL_RETRY_DELAY_MILLIS,
    private val logger: Logger = Logger.getLogger(TelegramLongPollingRunner::class.java.name),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(handleUpdate: suspend (Update) -> Unit) {
        var lastProcessedUpdateId: Long? = null

        while (true) {
            currentCoroutineContext().ensureActive()

            try {
                val updates = updatesFetcher.fetch(lastProcessedUpdateId?.plus(1))

                for (update in updates) {
                    currentCoroutineContext().ensureActive()
                    handleUpdate(update)
                    lastProcessedUpdateId = maxOf(lastProcessedUpdateId ?: update.updateId.long, update.updateId.long)
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }

                logger.warning(throwable.toRetryLogMessage(retryDelayMillis))
                sleep(retryDelayMillis)
            }
        }
    }
}

internal class TelegramPollingException(
    val errorCode: Int?,
    val description: String?,
) : IllegalStateException(
    buildString {
        append("Telegram getUpdates failed")
        errorCode?.let { append(" with error ").append(it) }
        description?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    },
)

private fun Throwable.toRetryLogMessage(retryDelayMillis: Long): String {
    val retrySeconds = retryDelayMillis / 1_000L

    return when (this) {
        is TelegramPollingException -> buildString {
            append("Telegram polling failed")
            errorCode?.let { append(" with error ").append(it) }
            description?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            append("; retrying in ").append(retrySeconds).append(" seconds.")
        }

        else -> "${javaClass.simpleName} during Telegram polling; retrying in $retrySeconds seconds."
    }
}
