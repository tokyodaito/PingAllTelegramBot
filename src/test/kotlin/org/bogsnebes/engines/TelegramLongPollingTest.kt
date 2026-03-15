package org.bogsnebes.engines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelegramLongPollingTest {
    @Test
    fun `get updates client builds request body and decodes updates`() = runTest {
        var capturedRequestBody: String? = null
        val client = TelegramGetUpdatesClient(
            requestJson = { requestBody ->
                capturedRequestBody = requestBody
                okResponse(messageUpdateJson(updateId = 101L, text = "ping"))
            },
            pollTimeoutSeconds = 30,
            allowedUpdates = listOf("message", "chat_member"),
        )

        val updates = client.fetch(41L)
        val requestBody = capturedRequestBody ?: error("request body was not captured")

        assertEquals(1, updates.size)
        assertEquals(101L, updates.single().updateId.long)
        assertTrue(requestBody.contains(""""offset":41"""))
        assertTrue(requestBody.contains(""""limit":100"""))
        assertTrue(requestBody.contains(""""timeout":30"""))
        assertTrue(requestBody.contains(""""allowed_updates":["message","chat_member"]"""))
    }

    @Test
    fun `get updates client surfaces telegram api errors`() = runTest {
        val client = TelegramGetUpdatesClient(
            requestJson = {
                """
                {
                  "ok": false,
                  "error_code": 409,
                  "description": "Conflict: terminated by other getUpdates request"
                }
                """.trimIndent()
            },
            pollTimeoutSeconds = 30,
            allowedUpdates = listOf("message", "chat_member"),
        )

        val error = assertFailsWith<TelegramPollingException> {
            client.fetch(null)
        }

        assertEquals(409, error.errorCode)
        assertEquals("Conflict: terminated by other getUpdates request", error.description)
    }

    @Test
    fun `runner retries failed update without advancing offset`() = runTest {
        val firstUpdate = decodeLibraryUpdate(messageUpdateJson(updateId = 1L, text = "first"))
        val secondUpdate = decodeLibraryUpdate(messageUpdateJson(updateId = 2L, text = "second"))
        val requestedOffsets = mutableListOf<Long?>()
        val sleepCalls = mutableListOf<Long>()
        val handledUpdateIds = mutableListOf<Long>()
        var callIndex = 0
        var failSecondUpdateOnce = true

        val runner = TelegramLongPollingRunner(
            updatesFetcher = TelegramUpdatesFetcher { offset ->
                requestedOffsets += offset
                when (callIndex++) {
                    0 -> listOf(firstUpdate, secondUpdate)
                    1 -> listOf(secondUpdate)
                    else -> throw CancellationException("stop polling")
                }
            },
            sleep = { sleepCalls += it },
        )

        assertFailsWith<CancellationException> {
            runner.run { update ->
                handledUpdateIds += update.updateId.long
                if (update.updateId.long == 2L && failSecondUpdateOnce) {
                    failSecondUpdateOnce = false
                    throw IllegalStateException("handler failed")
                }
            }
        }

        assertEquals(listOf<Long?>(null, 2L, 3L), requestedOffsets)
        assertEquals(listOf(1L, 2L, 2L), handledUpdateIds)
        assertEquals(listOf(5_000L), sleepCalls)
    }

    @Test
    fun `runner advances offset after handler intentionally ignores update`() = runTest {
        val firstUpdate = decodeLibraryUpdate(messageUpdateJson(updateId = 5L, text = "skip"))
        val secondUpdate = decodeLibraryUpdate(messageUpdateJson(updateId = 6L, text = "handle"))
        val requestedOffsets = mutableListOf<Long?>()
        val handledUpdateIds = mutableListOf<Long>()
        var callIndex = 0

        val runner = TelegramLongPollingRunner(
            updatesFetcher = TelegramUpdatesFetcher { offset ->
                requestedOffsets += offset
                when (callIndex++) {
                    0 -> listOf(firstUpdate, secondUpdate)
                    else -> throw CancellationException("stop polling")
                }
            },
            sleep = { error("sleep should not be called on successful polling") },
        )

        assertFailsWith<CancellationException> {
            runner.run { update ->
                if (update.updateId.long == 6L) {
                    handledUpdateIds += update.updateId.long
                }
            }
        }

        assertEquals(listOf<Long?>(null, 7L), requestedOffsets)
        assertEquals(listOf(6L), handledUpdateIds)
    }

    private fun okResponse(vararg updates: String): String = """
        {
          "ok": true,
          "result": [
            ${updates.joinToString(",\n")}
          ]
        }
    """.trimIndent()

    private fun messageUpdateJson(updateId: Long, text: String): String = """
        {
          "update_id": $updateId,
          "message": {
            "message_id": $updateId,
            "date": 1773489720,
            "chat": {
              "id": -200,
              "type": "supergroup",
              "title": "Team"
            },
            "from": {
              "id": 10,
              "is_bot": false,
              "first_name": "Owner",
              "username": "owner"
            },
            "text": "$text"
          }
        }
    """.trimIndent()
}
