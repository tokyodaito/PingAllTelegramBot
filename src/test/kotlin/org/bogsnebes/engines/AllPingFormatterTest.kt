package org.bogsnebes.engines

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllPingFormatterTest {
    @Test
    fun `builds single chunk with default no response statuses`() {
        val session = AllPingSession(
            id = 1L,
            chatId = -100L,
            messageThreadId = null,
            announcement = "Подъем",
            status = AllPingSessionStatus.ACTIVE,
            createdAt = Instant.EPOCH,
            closedAt = null,
            messages = listOf(AllPingSessionMessage(chunkIndex = 0, messageId = 1L)),
            participants = listOf(
                AllPingParticipant(username = "alice", position = 0, chunkIndex = 0, response = null),
                AllPingParticipant(username = "bob", position = 1, chunkIndex = 0, response = AllPingResponse.THINK),
            ),
        )

        val messages = AllPingFormatter.buildMessageChunks(session)

        assertEquals(1, messages.size)
        assertEquals(
            """
            Сбор ответа
            Подъем

            @alice - без ответа
            @bob - думает
            """.trimIndent(),
            messages.single(),
        )
    }

    @Test
    fun `prepares multiple chunks for oversized lists`() {
        val usernames = (1..220).map { "user$it" }

        val chunkIndexes = AllPingFormatter.prepareChunkIndexes(usernames, "Подъем")

        assertTrue(chunkIndexes.distinct().size > 1)
        assertEquals(0, chunkIndexes.first())
        assertTrue(chunkIndexes.last() > 0)
    }
}
