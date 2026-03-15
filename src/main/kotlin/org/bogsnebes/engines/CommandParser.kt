package org.bogsnebes.engines

sealed interface BotCommand

data class AllCommand(val announcement: String?) : BotCommand

data class AddCommand(val usernames: List<String>) : BotCommand

data object InvalidAddCommand : BotCommand

object CommandParser {
    private val commandRegex = Regex("^/(all|add)(?:@([A-Za-z0-9_]+))?$", RegexOption.IGNORE_CASE)
    private val usernameRegex = Regex("^@([A-Za-z0-9_]{1,32})$")

    fun parse(text: String, botUsername: String?): BotCommand? {
        val normalizedText = text.trimStart()
        val commandToken = normalizedText.takeWhile { !it.isWhitespace() }
        val match = commandRegex.matchEntire(commandToken) ?: return null
        val mentionedUsername = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }

        if (mentionedUsername != null && !mentionedUsername.equals(botUsername, ignoreCase = true)) {
            return null
        }

        val remainder = normalizedText.removePrefix(commandToken).trim()
        return when (match.groupValues[1].lowercase()) {
            "all" -> AllCommand(remainder.ifBlank { null })
            "add" -> parseAddCommand(remainder)
            else -> null
        }
    }

    private fun parseAddCommand(arguments: String): BotCommand {
        val tokens = arguments.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return InvalidAddCommand
        }

        val usernames = linkedSetOf<String>()
        for (token in tokens) {
            val match = usernameRegex.matchEntire(token) ?: return InvalidAddCommand
            usernames += match.groupValues[1].lowercase()
        }

        return AddCommand(usernames.toList())
    }
}
