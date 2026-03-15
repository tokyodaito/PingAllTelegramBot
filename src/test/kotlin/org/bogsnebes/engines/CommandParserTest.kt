package org.bogsnebes.engines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandParserTest {
    @Test
    fun `parses bare all command`() {
        val command = CommandParser.parse("/all", "PingAllBot")

        assertEquals(AllCommand(null), command)
    }

    @Test
    fun `parses addressed command and announcement`() {
        val command = CommandParser.parse("/all@pingallbot Срочно все сюда", "PingAllBot")

        assertEquals(AllCommand("Срочно все сюда"), command)
    }

    @Test
    fun `ignores command for another bot`() {
        val command = CommandParser.parse("/all@OtherBot hi", "PingAllBot")

        assertNull(command)
    }

    @Test
    fun `parses add command with normalization and deduplication`() {
        val command = CommandParser.parse("/add @Alice @bob @ALICE", "PingAllBot")

        assertEquals(
            AddCommand(
                listOf(
                    PingTagTarget.forUsername("alice"),
                    PingTagTarget.forUsername("bob"),
                ),
            ),
            command,
        )
    }

    @Test
    fun `parses add command with text mention target`() {
        val command = CommandParser.parse(
            text = "/add Sim",
            botUsername = "PingAllBot",
            textSources = listOf(
                TelegramRegularTextSource("/add "),
                TelegramTextMentionTextSource(
                    user = TelegramUser(id = 77L, isBot = false, firstName = "Sim", username = null),
                    source = "Sim",
                ),
            ),
        )

        assertEquals(
            AddCommand(
                listOf(
                    PingTagTarget(
                        identityKey = "u:77",
                        userId = 77L,
                        username = null,
                        displayNameSnapshot = "Sim",
                    ),
                ),
            ),
            command,
        )
    }

    @Test
    fun `returns invalid add command for missing args`() {
        val command = CommandParser.parse("/add", "PingAllBot")

        assertEquals(InvalidAddCommand, command)
    }

    @Test
    fun `returns invalid add command for invalid username token`() {
        val command = CommandParser.parse("/add @alice bob", "PingAllBot")

        assertEquals(InvalidAddCommand, command)
    }
}
