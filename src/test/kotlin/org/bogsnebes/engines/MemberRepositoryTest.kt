package org.bogsnebes.engines

import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemberRepositoryTest {
    @Test
    fun `updates existing member and filters stale or left users`() {
        val dbPath = Files.createTempFile("members", ".db")

        try {
            MemberRepository(dbPath).use { repository ->
                repository.upsertSeenMember(
                    chatId = 10L,
                    user = TelegramUser(id = 1L, isBot = false, firstName = "Alice"),
                    status = "member",
                    seenAt = Instant.parse("2026-03-14T10:00:00Z"),
                    source = "message",
                )
                repository.upsertSeenMember(
                    chatId = 10L,
                    user = TelegramUser(id = 1L, isBot = false, firstName = "Alice", lastName = "Smith"),
                    status = "administrator",
                    seenAt = Instant.parse("2026-03-14T11:00:00Z"),
                    source = "chat_member",
                )
                repository.upsertSeenMember(
                    chatId = 10L,
                    user = TelegramUser(id = 2L, isBot = false, firstName = "Bob"),
                    status = "left",
                    seenAt = Instant.parse("2026-03-14T11:00:00Z"),
                    source = "chat_member",
                )
                repository.upsertSeenMember(
                    chatId = 10L,
                    user = TelegramUser(id = 3L, isBot = false, firstName = "Carol"),
                    status = "member",
                    seenAt = Instant.parse("2026-03-01T11:00:00Z"),
                    source = "message",
                )

                val members = repository.listMentionableMembers(
                    chatId = 10L,
                    activeSince = Instant.parse("2026-03-07T00:00:00Z"),
                )

                assertEquals(1, members.size)
                assertEquals("Alice Smith", members.single().displayNameSnapshot)
                assertEquals("administrator", members.single().status)
            }
        } finally {
            assertTrue(dbPath.deleteIfExists())
        }
    }

    @Test
    fun `replaces ping tags per chat preserving order`() {
        val dbPath = Files.createTempFile("members-tags", ".db")

        try {
            MemberRepository(dbPath).use { repository ->
                repository.replacePingTags(10L, listOf("alice", "bob"))
                repository.replacePingTags(20L, listOf("other"))
                repository.replacePingTags(10L, listOf("charlie", "alice"))

                assertEquals(listOf("charlie", "alice"), repository.listPingTags(10L))
                assertEquals(listOf("other"), repository.listPingTags(20L))
            }
        } finally {
            assertTrue(dbPath.deleteIfExists())
        }
    }
}
