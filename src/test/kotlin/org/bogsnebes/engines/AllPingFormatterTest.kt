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
                AllPingParticipant(
                    identityKey = "n:alice",
                    username = "alice",
                    displayNameSnapshot = "@alice",
                    position = 0,
                    chunkIndex = 0,
                    response = null,
                ),
                AllPingParticipant(
                    identityKey = "n:bob",
                    username = "bob",
                    displayNameSnapshot = "@bob",
                    position = 1,
                    chunkIndex = 0,
                    response = AllPingResponse.THINK,
                ),
            ),
        )

        val messages = AllPingFormatter.buildMessageChunks(session)

        assertEquals(1, messages.size)
        assertEquals(
            """
            Сбор ответа
            Подъем

            @alice - без ответа ⏳
            @bob - думает 🤔
            """.trimIndent(),
            messages.single(),
        )
    }

    @Test
    fun `prepares multiple chunks for oversized lists`() {
        val targets = (1..220).map { PingTagTarget.forUsername("user$it") }

        val chunkIndexes = AllPingFormatter.prepareChunkIndexes(targets, "Подъем")

        assertTrue(chunkIndexes.distinct().size > 1)
        assertEquals(0, chunkIndexes.first())
        assertTrue(chunkIndexes.last() > 0)
    }

    @Test
    fun `renders user mention target by user id`() {
        val session = AllPingSession(
            id = 1L,
            chatId = -100L,
            messageThreadId = null,
            announcement = null,
            status = AllPingSessionStatus.ACTIVE,
            createdAt = Instant.EPOCH,
            closedAt = null,
            messages = listOf(AllPingSessionMessage(chunkIndex = 0, messageId = 1L)),
            participants = listOf(
                AllPingParticipant(
                    identityKey = "u:77",
                    userId = 77L,
                    username = null,
                    displayNameSnapshot = "Sim",
                    position = 0,
                    chunkIndex = 0,
                    response = null,
                ),
            ),
        )

        val messages = AllPingFormatter.buildMessageChunks(session)

        assertEquals("Сбор ответа\n\n<a href=\"tg://user?id=77\">Sim</a> - без ответа ⏳", messages.single())
    }

    @Test
    fun `long announcement stays within telegram limit and may take first chunk alone`() {
        val longAnnouncement = "A".repeat(4_096)
        val targets = listOf(PingTagTarget.forUsername("alice"))
        val chunkIndexes = AllPingFormatter.prepareChunkIndexes(targets, longAnnouncement)
        val session = AllPingSession(
            id = 1L,
            chatId = -100L,
            messageThreadId = null,
            announcement = longAnnouncement,
            status = AllPingSessionStatus.ACTIVE,
            createdAt = Instant.EPOCH,
            closedAt = null,
            messages = listOf(
                AllPingSessionMessage(chunkIndex = 0, messageId = 1L),
                AllPingSessionMessage(chunkIndex = 1, messageId = 2L),
            ),
            participants = listOf(
                AllPingParticipant(
                    identityKey = "n:alice",
                    userId = null,
                    username = "alice",
                    displayNameSnapshot = "@alice",
                    position = 0,
                    chunkIndex = chunkIndexes.single(),
                    response = null,
                ),
            ),
        )

        val messages = AllPingFormatter.buildMessageChunks(session)

        assertEquals(listOf(1), chunkIndexes)
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.length <= 4_096 })
        assertEquals("Сбор ответа\n${"A".repeat(4_081)}...", messages.first())
        assertEquals("@alice - без ответа ⏳", messages.last())
    }
}
