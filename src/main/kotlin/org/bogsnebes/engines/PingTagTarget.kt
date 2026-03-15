package org.bogsnebes.engines

data class PingTagTarget(
    val identityKey: String,
    val userId: Long? = null,
    val username: String? = null,
    val displayNameSnapshot: String,
) {
    companion object {
        fun forUsername(username: String): PingTagTarget {
            val normalizedUsername = username.lowercase()
            return PingTagTarget(
                identityKey = identityKeyForUsername(normalizedUsername),
                username = normalizedUsername,
                displayNameSnapshot = "@$normalizedUsername",
            )
        }

        fun forUser(user: TelegramUser, displayNameSnapshot: String): PingTagTarget = PingTagTarget(
            identityKey = identityKeyForUser(user.id),
            userId = user.id,
            username = user.username?.lowercase(),
            displayNameSnapshot = displayNameSnapshot.trim().ifEmpty { user.toDisplayName() },
        )

        fun identityKeyForUser(userId: Long): String = "u:$userId"

        fun identityKeyForUsername(username: String): String = "n:${username.lowercase()}"
    }
}
