package org.bogsnebes.engines

object MentionFormatter {
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
}
