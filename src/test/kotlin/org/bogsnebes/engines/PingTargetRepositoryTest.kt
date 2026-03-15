package org.bogsnebes.engines

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PingTargetRepositoryTest {
    @Test
    fun `replaces ping targets per chat preserving order`() {
        val dbPath = Files.createTempFile("ping-targets", ".db")

        try {
            PingTargetRepository(dbPath).use { repository ->
                repository.replaceTargets(10L, listOf(PingTagTarget.forUsername("alice"), PingTagTarget.forUsername("bob")))
                repository.replaceTargets(20L, listOf(PingTagTarget.forUsername("other")))
                repository.replaceTargets(10L, listOf(PingTagTarget.forUsername("charlie"), PingTagTarget.forUsername("alice")))

                assertEquals(
                    listOf(PingTagTarget.forUsername("charlie"), PingTagTarget.forUsername("alice")),
                    repository.listTargets(10L),
                )
                assertEquals(listOf(PingTagTarget.forUsername("other")), repository.listTargets(20L))
            }
        } finally {
            assertTrue(dbPath.deleteIfExists())
        }
    }

    @Test
    fun `migrates legacy ping tags table to identity based targets`() {
        val dbPath = Files.createTempFile("ping-targets-legacy", ".db")

        try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE ping_tags (
                            chat_id INTEGER NOT NULL,
                            position INTEGER NOT NULL,
                            username TEXT NOT NULL,
                            PRIMARY KEY(chat_id, username)
                        )
                        """.trimIndent(),
                    )
                    statement.execute("INSERT INTO ping_tags (chat_id, position, username) VALUES (10, 0, 'Alice')")
                    statement.execute("INSERT INTO ping_tags (chat_id, position, username) VALUES (10, 1, 'Bob')")
                }
            }

            PingTargetRepository(dbPath).use { repository ->
                assertEquals(
                    listOf(
                        PingTagTarget(
                            identityKey = "n:alice",
                            userId = null,
                            username = "alice",
                            displayNameSnapshot = "@alice",
                        ),
                        PingTagTarget(
                            identityKey = "n:bob",
                            userId = null,
                            username = "bob",
                            displayNameSnapshot = "@bob",
                        ),
                    ),
                    repository.listTargets(10L),
                )
            }
        } finally {
            assertTrue(dbPath.deleteIfExists())
        }
    }
}
