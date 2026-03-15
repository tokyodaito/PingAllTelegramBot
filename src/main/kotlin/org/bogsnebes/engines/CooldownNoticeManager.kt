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

            val sentMessage = telegramGateway.sendMessage(
                chatId = chatId,
                text = buildCooldownText(remainingUntil(availableAt)),
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
        var lastText = buildCooldownText(remainingUntil(availableAt))

        while (true) {
            val remaining = remainingUntil(availableAt)
            if (!isActive(remaining)) {
                removeAndDeleteIfCurrent(chatId, messageId)
                return
            }

            delay(remaining.toMillis().coerceAtLeast(1L).coerceAtMost(1_000L))

            val nextRemaining = remainingUntil(availableAt)
            if (!isActive(nextRemaining)) {
                removeAndDeleteIfCurrent(chatId, messageId)
                return
            }

            val nextText = buildCooldownText(nextRemaining)
            if (nextText == lastText) {
                continue
            }

            val updated = editIfCurrent(chatId, messageId, nextText)
            if (!updated) {
                return
            }

            lastText = nextText
        }
    }

    private suspend fun editIfCurrent(
        chatId: Long,
        messageId: Long,
        text: String,
    ): Boolean = mutexFor(chatId).withLock {
        val currentNotice = activeNoticesByChat[chatId] ?: return@withLock false
        if (currentNotice.messageId != messageId) {
            return@withLock false
        }

        try {
            telegramGateway.editMessageText(
                chatId = chatId,
                messageId = messageId,
                text = text,
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            logger.log(
                Level.WARNING,
                "Failed to edit cooldown notice $messageId in chat $chatId",
                throwable,
            )
            activeNoticesByChat.remove(chatId)
            false
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

    private fun mutexFor(chatId: Long): Mutex = mutexesByChat.computeIfAbsent(chatId) { Mutex() }

    private data class ActiveCooldownNotice(
        val messageId: Long,
        val messageThreadId: Long?,
        val availableAt: Instant,
        val job: Job,
    )
}
