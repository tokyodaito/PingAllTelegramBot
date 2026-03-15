package org.bogsnebes.engines

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class PingCooldownTracker(private val cooldown: Duration) {
    private val nextAllowedAtByChat = ConcurrentHashMap<Long, Instant>()

    @Synchronized
    fun remaining(chatId: Long, now: Instant): Duration? {
        val nextAllowedAt = nextAllowedAtByChat[chatId]
        if (nextAllowedAt != null && nextAllowedAt.isAfter(now)) {
            return Duration.between(now, nextAllowedAt)
        }

        return null
    }

    fun remainingSince(startedAt: Instant, now: Instant): Duration? {
        val nextAllowedAt = startedAt.plus(cooldown)
        return if (nextAllowedAt.isAfter(now)) {
            Duration.between(now, nextAllowedAt)
        } else {
            null
        }
    }

    @Synchronized
    fun reserve(chatId: Long, now: Instant): Instant {
        val nextAllowedAt = now.plus(cooldown)
        nextAllowedAtByChat[chatId] = nextAllowedAt
        return nextAllowedAt
    }

    @Synchronized
    fun reserveUntil(chatId: Long, nextAllowedAt: Instant): Instant {
        val current = nextAllowedAtByChat[chatId]
        if (current == null || nextAllowedAt.isAfter(current)) {
            nextAllowedAtByChat[chatId] = nextAllowedAt
            return nextAllowedAt
        }

        return current
    }

    @Synchronized
    fun clear(chatId: Long) {
        nextAllowedAtByChat.remove(chatId)
    }
}
