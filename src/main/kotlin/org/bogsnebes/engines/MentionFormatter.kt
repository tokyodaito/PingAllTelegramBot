package org.bogsnebes.engines

object MentionFormatter {
    private const val maxMentionsPerMessage = 40
    private const val maxMessageLength = 4_096

    fun buildMessages(members: List<KnownMember>, announcement: String?): List<String> {
        return buildMessageChunks(
            mentions = members.map(::renderKnownMember),
            announcement = announcement,
        )
    }

    fun buildTagMessages(usernames: List<String>, announcement: String?): List<String> {
        return buildMessageChunks(
            mentions = usernames.map { renderTarget(username = it, displayNameSnapshot = "@$it") },
            announcement = announcement,
        )
    }

    fun renderTarget(target: PingTagTarget): String = renderTarget(
        userId = target.userId,
        username = target.username,
        displayNameSnapshot = target.displayNameSnapshot,
    )

    fun renderTarget(
        userId: Long? = null,
        username: String? = null,
        displayNameSnapshot: String,
    ): String = when {
        userId != null -> """<a href="tg://user?id=$userId">${escapeHtml(displayNameSnapshot)}</a>"""
        !username.isNullOrBlank() -> "@${escapeHtml(username)}"
        else -> escapeHtml(displayNameSnapshot)
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

    private fun renderKnownMember(member: KnownMember): String = renderTarget(
        userId = member.userId,
        username = member.username,
        displayNameSnapshot = member.displayNameSnapshot,
    )

    private fun buildMessageChunks(mentions: List<String>, announcement: String?): List<String> {
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

    private fun truncateToLimit(value: String): String {
        if (value.length <= maxMessageLength) {
            return value
        }
        return value.take(maxMessageLength - 1) + "…"
    }
}
