package org.bogsnebes.engines

data class AllCommand(val announcement: String?)

object CommandParser {
    private val commandRegex = Regex("^/all(?:@([A-Za-z0-9_]+))?$")

    fun parseAllCommand(text: String, botUsername: String?): AllCommand? {
        val normalizedText = text.trimStart()
        val commandToken = normalizedText.takeWhile { !it.isWhitespace() }
        val match = commandRegex.matchEntire(commandToken) ?: return null
        val mentionedUsername = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }

        if (mentionedUsername != null && !mentionedUsername.equals(botUsername, ignoreCase = true)) {
            return null
        }

        val remainder = normalizedText.removePrefix(commandToken).trim()
        return AllCommand(remainder.ifBlank { null })
    }
}
