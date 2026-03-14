package org.bogsnebes.engines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MentionFormatterTest {
    @Test
    fun `keeps announcement in first chunk`() {
        val members = listOf(
            knownMember(1),
            knownMember(2),
        )

        val messages = MentionFormatter.buildMessages(members, "Внимание <всем>")

        assertEquals(1, messages.size)
        assertTrue(messages.first().startsWith("Внимание &lt;всем&gt;\n\n"))
    }

    @Test
    fun `splits large mention list into multiple chunks`() {
        val members = (1L..85L).map(::knownMember)

        val messages = MentionFormatter.buildMessages(members, null)

        assertEquals(3, messages.size)
    }

    private fun knownMember(id: Long): KnownMember = KnownMember(
        chatId = 1L,
        userId = id,
        firstName = "User$id",
        lastName = null,
        username = "user$id",
        displayNameSnapshot = "User$id",
        isBot = false,
        status = "member",
        firstSeenAt = java.time.Instant.EPOCH,
        lastSeenAt = java.time.Instant.EPOCH,
        lastSeenSource = "message",
    )
}
