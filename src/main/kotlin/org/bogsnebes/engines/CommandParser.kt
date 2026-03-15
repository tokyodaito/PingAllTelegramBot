package org.bogsnebes.engines

sealed interface BotCommand

data class AllCommand(val announcement: String?) : BotCommand

data class AddCommand(val targets: List<PingTagTarget>) : BotCommand

data object InvalidAddCommand : BotCommand

object CommandParser {
    private val commandRegex = Regex("^/(all|add)(?:@([A-Za-z0-9_]+))?$", RegexOption.IGNORE_CASE)
    private val usernameRegex = Regex("^@([A-Za-z0-9_]{1,32})$")

    fun parse(
        text: String,
        botUsername: String?,
        textSources: List<TelegramMessageTextSource> = emptyList(),
    ): BotCommand? {
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
            "add" -> parseAddCommand(
                arguments = remainder,
                textSources = textSources.dropPrefix(text.length - normalizedText.length + commandToken.length),
            )
            else -> null
        }
    }

    private fun parseAddCommand(arguments: String, textSources: List<TelegramMessageTextSource>): BotCommand {
        val tokens = tokenizeAddArguments(arguments, textSources)
        if (tokens.isEmpty()) {
            return InvalidAddCommand
        }

        val targets = linkedMapOf<String, PingTagTarget>()
        for (token in tokens) {
            val target = when (token) {
                is AddArgumentToken.RawText -> {
                    val match = usernameRegex.matchEntire(token.value) ?: return InvalidAddCommand
                    PingTagTarget.forUsername(match.groupValues[1])
                }

                is AddArgumentToken.UsernameMention -> PingTagTarget.forUsername(token.username)
                is AddArgumentToken.UserMention -> PingTagTarget.forUser(token.user, token.label)
            }
            targets.putIfAbsent(target.identityKey, target)
        }

        return AddCommand(targets.values.toList())
    }

    private fun tokenizeAddArguments(
        arguments: String,
        textSources: List<TelegramMessageTextSource>,
    ): List<AddArgumentToken> {
        if (arguments.isBlank()) {
            return emptyList()
        }
        if (textSources.isEmpty()) {
            return arguments
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map(AddArgumentToken::RawText)
        }

        val tokens = mutableListOf<AddArgumentToken>()
        val builder = StringBuilder()

        fun flushRawToken() {
            if (builder.isNotEmpty()) {
                tokens += AddArgumentToken.RawText(builder.toString())
                builder.clear()
            }
        }

        textSources.forEach { source ->
            when (source) {
                is TelegramRegularTextSource -> {
                    source.source.forEach { character ->
                        if (character.isWhitespace()) {
                            flushRawToken()
                        } else {
                            builder.append(character)
                        }
                    }
                }

                is TelegramMentionTextSource -> {
                    flushRawToken()
                    tokens += AddArgumentToken.UsernameMention(source.username)
                }

                is TelegramTextMentionTextSource -> {
                    flushRawToken()
                    tokens += AddArgumentToken.UserMention(
                        user = source.user,
                        label = source.source,
                    )
                }
            }
        }

        flushRawToken()
        return tokens
    }

    private fun List<TelegramMessageTextSource>.dropPrefix(prefixLength: Int): List<TelegramMessageTextSource> {
        if (prefixLength <= 0) {
            return this
        }

        var remaining = prefixLength
        val trimmedSources = mutableListOf<TelegramMessageTextSource>()
        for (source in this) {
            val length = source.source.length
            if (remaining >= length) {
                remaining -= length
                continue
            }

            if (remaining == 0) {
                trimmedSources += source
                continue
            }

            val trimmedSource = source.trimmedPrefix(remaining)
            if (trimmedSource.source.isNotEmpty()) {
                trimmedSources += trimmedSource
            }
            remaining = 0
        }
        return trimmedSources
    }

    private fun TelegramMessageTextSource.trimmedPrefix(prefixLength: Int): TelegramMessageTextSource = when (this) {
        is TelegramRegularTextSource -> copy(source = source.drop(prefixLength))
        is TelegramMentionTextSource -> copy(source = source.drop(prefixLength))
        is TelegramTextMentionTextSource -> copy(source = source.drop(prefixLength))
    }

    private sealed interface AddArgumentToken {
        data class RawText(val value: String) : AddArgumentToken
        data class UsernameMention(val username: String) : AddArgumentToken
        data class UserMention(val user: TelegramUser, val label: String) : AddArgumentToken
    }
}
