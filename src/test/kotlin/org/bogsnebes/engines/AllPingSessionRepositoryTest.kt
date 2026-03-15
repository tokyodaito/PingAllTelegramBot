package org.bogsnebes.engines

import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllPingSessionRepositoryTest {
    @Test
    fun `migrates legacy all ping participants to identity based schema`() {
        val dbPath = Files.createTempFile("all-ping-legacy", ".db")

        try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE all_ping_sessions (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            chat_id INTEGER NOT NULL,
                            message_thread_id INTEGER,
                            announcement TEXT,
                            status TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            closed_at INTEGER
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE all_ping_session_messages (
                            session_id INTEGER NOT NULL,
                            chunk_index INTEGER NOT NULL,
                            message_id INTEGER NOT NULL,
                            PRIMARY KEY(session_id, chunk_index)
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        CREATE TABLE all_ping_session_participants (
                            session_id INTEGER NOT NULL,
                            username TEXT NOT NULL,
                            position INTEGER NOT NULL,
                            chunk_index INTEGER NOT NULL,
                            response_code TEXT,
                            responded_at INTEGER,
                            PRIMARY KEY(session_id, username)
                        )
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO all_ping_sessions (
                            id, chat_id, message_thread_id, announcement, status, created_at, closed_at
                        ) VALUES (1, -100, NULL, 'Wake up', 'ACTIVE', 0, NULL)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO all_ping_session_messages (session_id, chunk_index, message_id)
                        VALUES (1, 0, 99)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO all_ping_session_participants (
                            session_id, username, position, chunk_index, response_code, responded_at
                        ) VALUES (1, 'Alice', 0, 0, NULL, NULL)
                        """.trimIndent(),
                    )
                }
            }

            AllPingSessionRepository(dbPath).use { repository ->
                val session = assertNotNull(repository.findSession(1L))
                assertEquals(
                    AllPingParticipant(
                        identityKey = "n:alice",
                        userId = null,
                        username = "alice",
                        displayNameSnapshot = "@alice",
                        position = 0,
                        chunkIndex = 0,
                        response = null,
                    ),
                    session.participants.single(),
                )
            }
        } finally {
            assertTrue(dbPath.deleteIfExists())
        }
    }

    @Test
    fun `stores pending session and activates it after publish`() {
        val dbPath = Files.createTempFile("all-ping-targets", ".db")

        try {
            AllPingSessionRepository(dbPath).use { repository ->
                val session = repository.createPendingSession(
                    chatId = -100L,
                    messageThreadId = null,
                    announcement = "Wake up",
                    targets = listOf(PingTagTarget.forUsername("alice")),
                    createdAt = Instant.EPOCH,
                )

                assertEquals(AllPingSessionStatus.PENDING, session.status)
                assertNull(repository.findActiveSession(-100L))
                assertTrue(repository.activateSession(session.id))
                assertEquals(AllPingSessionStatus.ACTIVE, repository.findActiveSession(-100L)?.status)
            }
        } finally {
            assertTrue(dbPath.deleteIfExists())
        }
    }

    @Test
    fun `stores incremental message ids for pending publish`() {
        val dbPath = Files.createTempFile("all-ping-messages", ".db")

        try {
            AllPingSessionRepository(dbPath).use { repository ->
                val session = repository.createPendingSession(
                    chatId = -100L,
                    messageThreadId = null,
                    announcement = "Wake up",
                    targets = listOf(PingTagTarget.forUsername("alice"), PingTagTarget.forUsername("bob")),
                    createdAt = Instant.EPOCH,
                )

                repository.saveMessages(
                    session.id,
                    listOf(TelegramSentMessage(chatId = -100L, messageId = 11L)),
                )
                assertEquals(listOf(11L), repository.findSession(session.id)?.messages?.map(AllPingSessionMessage::messageId))

                repository.saveMessages(
                    session.id,
                    listOf(
                        TelegramSentMessage(chatId = -100L, messageId = 11L),
                        TelegramSentMessage(chatId = -100L, messageId = 12L),
                    ),
                )
                assertEquals(
                    listOf(11L, 12L),
                    repository.findSession(session.id)?.messages?.map(AllPingSessionMessage::messageId),
                )
            }
        } finally {
            assertTrue(dbPath.deleteIfExists())
        }
    }
}
