package org.bogsnebes.engines

object MentionFormatter {
    private const val maxMentionsPerMessage = 40
    private const val maxMessageLength = 4_096

    fun buildMessages(members: List<KnownMember>, announcement: String?): List<String> {
        val mentions = members.map(::toMention)
        val header = announcement
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::escapeHtml)
            ?.let(::truncateToLimit)

        val messages = mutableListOf<String>()
        var mentionIndex = 0
        var firstMessage = true

        while (mentionIndex < mentions.size || (firstMessage && header != null)) {
            val prefix = if (firstMessage) header else null
            val builder = StringBuilder()
            if (prefix != null) {
                builder.append(prefix)
            }

            var count = 0
            while (mentionIndex < mentions.size && count < maxMentionsPerMessage) {
                val separator = when {
                    builder.isEmpty() -> ""
                    count == 0 && prefix != null -> "\n\n"
                    else -> " "
                }
                val nextMention = mentions[mentionIndex]
                if (builder.length + separator.length + nextMention.length > maxMessageLength) {
                    if (count == 0 && prefix == null) {
                        builder.append(nextMention.take(maxMessageLength))
                        mentionIndex += 1
                        count += 1
                    }
                    break
                }
                builder.append(separator)
                builder.append(nextMention)
                mentionIndex += 1
                count += 1
            }

            if (builder.isNotEmpty()) {
                messages += builder.toString()
            }
            firstMessage = false
        }

        return messages
    }

    fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(character)
            }
        }
    }

    private fun toMention(member: KnownMember): String {
        return """<a href="tg://user?id=${member.userId}">${escapeHtml(member.displayNameSnapshot)}</a>"""
    }

    private fun truncateToLimit(value: String): String {
        if (value.length <= maxMessageLength) {
            return value
        }
        return value.take(maxMessageLength - 1) + "…"
    }
}
