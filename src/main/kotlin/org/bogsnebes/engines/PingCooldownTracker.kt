package org.bogsnebes.engines

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class PingCooldownTracker(private val cooldown: Duration) {
    private val nextAllowedAtByChat = ConcurrentHashMap<Long, Instant>()

    @Synchronized
    fun remainingOrReserve(chatId: Long, now: Instant): Duration? {
        val nextAllowedAt = nextAllowedAtByChat[chatId]
        if (nextAllowedAt != null && nextAllowedAt.isAfter(now)) {
            return Duration.between(now, nextAllowedAt)
        }

        nextAllowedAtByChat[chatId] = now.plus(cooldown)
        return null
    }
}
