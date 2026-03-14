package org.bogsnebes.engines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandParserTest {
    @Test
    fun `parses bare all command`() {
        val command = CommandParser.parseAllCommand("/all", "PingAllBot")

        assertEquals(AllCommand(null), command)
    }

    @Test
    fun `parses addressed command and announcement`() {
        val command = CommandParser.parseAllCommand("/all@pingallbot Срочно все сюда", "PingAllBot")

        assertEquals(AllCommand("Срочно все сюда"), command)
    }

    @Test
    fun `ignores command for another bot`() {
        val command = CommandParser.parseAllCommand("/all@OtherBot hi", "PingAllBot")

        assertNull(command)
    }
}
