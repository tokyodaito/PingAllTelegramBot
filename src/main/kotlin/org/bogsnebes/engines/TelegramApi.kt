package org.bogsnebes.engines

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

interface TelegramGateway {
    suspend fun sendMessage(chatId: Long, text: String, messageThreadId: Long? = null)
}

class TelegramApi(
    private val client: HttpClient,
    token: String,
) : TelegramGateway {
    private val baseUrl = "https://api.telegram.org/bot$token"

    suspend fun getMe(): TelegramUser = call<EmptyTelegramRequest, TelegramUser>(
        method = "getMe",
        request = EmptyTelegramRequest,
    )

    suspend fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ): List<TelegramUpdate> = call<GetUpdatesRequest, List<TelegramUpdate>>(
        method = "getUpdates",
        request = GetUpdatesRequest(
            offset = offset,
            timeout = timeoutSeconds,
            allowedUpdates = allowedUpdates,
        ),
    )

    override suspend fun sendMessage(chatId: Long, text: String, messageThreadId: Long?) {
        call<SendMessageRequest, TelegramMessage>(
            method = "sendMessage",
            request = SendMessageRequest(
                chatId = chatId,
                text = text,
                messageThreadId = messageThreadId,
            ),
        )
    }

    private suspend inline fun <reified Request : Any, reified Response> call(
        method: String,
        request: Request,
    ): Response {
        val response = client.post("$baseUrl/$method") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<TelegramResponse<Response>>()

        if (!response.ok || response.result == null) {
            throw TelegramApiException("Telegram API $method failed: ${response.description ?: "unknown error"}")
        }

        return response.result
    }
}

class TelegramApiException(message: String) : RuntimeException(message)
