package org.bogsnebes.engines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

private val COUNTDOWN_UPDATE_STEP: Duration = Duration.ofSeconds(10)

class CooldownNoticeManager(
    private val telegramGateway: TelegramGateway,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = Logger.getLogger(CooldownNoticeManager::class.java.name)
    private val activeNoticesByChat = ConcurrentHashMap<Long, ActiveCooldownNotice>()
    private val mutexesByChat = ConcurrentHashMap<Long, Mutex>()

    suspend fun showCooldownNotice(
        chatId: Long,
        messageThreadId: Long?,
        commandMessageId: Long,
        availableAt: Instant,
    ) {
        mutexFor(chatId).withLock {
            val previousNotice = activeNoticesByChat.remove(chatId)
            previousNotice?.job?.cancel()
            previousNotice?.let {
                deleteMessageIgnoringErrors(chatId, it.messageId, "previous cooldown notice")
            }

            deleteMessageIgnoringErrors(chatId, commandMessageId, "triggering command")

            val initialText = buildCooldownText(displayRemaining(remainingUntil(availableAt)))
            val sentMessage = telegramGateway.sendMessage(
                chatId = chatId,
                text = initialText,
                messageThreadId = messageThreadId,
            )
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runCountdown(
                        chatId = chatId,
                        messageId = sentMessage.messageId,
                        availableAt = availableAt,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    logger.log(
                        Level.WARNING,
                        "Cooldown notice updater failed for chat $chatId and message ${sentMessage.messageId}",
                        throwable,
                    )
                }
            }

            activeNoticesByChat[chatId] = ActiveCooldownNotice(
                messageId = sentMessage.messageId,
                messageThreadId = sentMessage.messageThreadId,
                availableAt = availableAt,
                job = job,
            )
            job.start()
        }
    }

    private suspend fun runCountdown(
        chatId: Long,
        messageId: Long,
        availableAt: Instant,
    ) {
        var lastDisplayedRemaining = displayRemaining(remainingUntil(availableAt))
        var lastText = buildCooldownText(lastDisplayedRemaining)
        var nextDelay = nextUpdateDelay(remainingUntil(availableAt))

        while (true) {
            delay(nextDelay.toMillis().coerceAtLeast(1L))

            val remaining = remainingUntil(availableAt)
            if (!isActive(remaining)) {
                removeAndDeleteIfCurrent(chatId, messageId)
                return
            }

            val nextDisplayedRemaining = displayRemaining(
                remaining = remaining,
                previousDisplayed = lastDisplayedRemaining,
            )
            val nextText = buildCooldownText(nextDisplayedRemaining)
            if (nextText == lastText) {
                nextDelay = nextUpdateDelay(remaining)
                continue
            }

            when (val result = editIfCurrent(chatId, messageId, nextText)) {
                EditResult.NotCurrent -> return
                is EditResult.RateLimited -> nextDelay = result.retryAfter
                EditResult.Updated -> {
                    lastDisplayedRemaining = nextDisplayedRemaining
                    lastText = nextText
                    nextDelay = nextUpdateDelay(remaining)
                }
            }
        }
    }

    private suspend fun editIfCurrent(
        chatId: Long,
        messageId: Long,
        text: String,
    ): EditResult = mutexFor(chatId).withLock {
        val currentNotice = activeNoticesByChat[chatId] ?: return@withLock EditResult.NotCurrent
        if (currentNotice.messageId != messageId) {
            return@withLock EditResult.NotCurrent
        }

        try {
            telegramGateway.editMessageText(
                chatId = chatId,
                messageId = messageId,
                text = text,
            )
            EditResult.Updated
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rateLimit: TelegramRateLimitException) {
            EditResult.RateLimited(rateLimit.retryAfter)
        } catch (throwable: Throwable) {
            logger.log(
                Level.WARNING,
                "Failed to edit cooldown notice $messageId in chat $chatId",
                throwable,
            )
            activeNoticesByChat.remove(chatId)
            EditResult.NotCurrent
        }
    }

    private suspend fun removeAndDeleteIfCurrent(
        chatId: Long,
        messageId: Long,
    ) {
        mutexFor(chatId).withLock {
            val currentNotice = activeNoticesByChat[chatId] ?: return@withLock
            if (currentNotice.messageId != messageId) {
                return@withLock
            }

            activeNoticesByChat.remove(chatId)
            deleteMessageIgnoringErrors(chatId, messageId, "cooldown notice")
        }
    }

    private suspend fun deleteMessageIgnoringErrors(
        chatId: Long,
        messageId: Long,
        description: String,
    ) {
        try {
            telegramGateway.deleteMessage(chatId = chatId, messageId = messageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            logger.log(
                Level.WARNING,
                "Failed to delete $description $messageId in chat $chatId",
                throwable,
            )
        }
    }

    private fun remainingUntil(availableAt: Instant): Duration = Duration.between(clock.instant(), availableAt)

    private fun buildCooldownText(remaining: Duration): String =
        "Команда /all сейчас на кулдауне. Попробуйте снова через ${formatCountdown(remaining)}."

    private fun isActive(duration: Duration): Boolean = duration > Duration.ZERO

    private fun formatCountdown(duration: Duration): String {
        val totalSeconds = when {
            duration.isNegative || duration.isZero -> 0L
            else -> (duration.toMillis() + 999L) / 1_000L
        }

        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L

        return if (minutes > 0L) {
            "${minutes}м ${seconds}с"
        } else {
            "${seconds}с"
        }
    }

    private fun displayRemaining(
        remaining: Duration,
        previousDisplayed: Duration? = null,
    ): Duration {
        val quantized = quantizeCountdown(remaining)
        return when {
            previousDisplayed == null -> quantized
            quantized > previousDisplayed -> previousDisplayed
            else -> quantized
        }
    }

    private fun quantizeCountdown(duration: Duration): Duration {
        val totalSeconds = when {
            duration.isNegative || duration.isZero -> 0L
            else -> (duration.toMillis() + 999L) / 1_000L
        }
        if (totalSeconds == 0L) {
            return Duration.ZERO
        }

        val stepSeconds = COUNTDOWN_UPDATE_STEP.seconds
        val quantizedSeconds = ((totalSeconds + stepSeconds - 1L) / stepSeconds) * stepSeconds
        return Duration.ofSeconds(quantizedSeconds)
    }

    private fun nextUpdateDelay(remaining: Duration): Duration = when {
        remaining <= Duration.ZERO -> Duration.ZERO
        remaining < COUNTDOWN_UPDATE_STEP -> remaining
        else -> COUNTDOWN_UPDATE_STEP
    }

    private fun mutexFor(chatId: Long): Mutex = mutexesByChat.computeIfAbsent(chatId) { Mutex() }

    private sealed interface EditResult {
        data object Updated : EditResult
        data object NotCurrent : EditResult
        data class RateLimited(val retryAfter: Duration) : EditResult
    }

    private data class ActiveCooldownNotice(
        val messageId: Long,
        val messageThreadId: Long?,
        val availableAt: Instant,
        val job: Job,
    )
}
