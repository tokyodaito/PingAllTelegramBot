package org.bogsnebes.engines

import kotlinx.coroutines.delay
import java.util.logging.Level
import java.util.logging.Logger

class PollingRunner(
    private val api: TelegramApi,
    private val botService: BotService,
    private val pollTimeoutSeconds: Int,
) {
    private val logger = Logger.getLogger(PollingRunner::class.java.name)

    suspend fun runForever() {
        var offset: Long? = null

        while (true) {
            try {
                val updates = api.getUpdates(
                    offset = offset,
                    timeoutSeconds = pollTimeoutSeconds,
                    allowedUpdates = listOf("message", "chat_member"),
                )

                for (update in updates.sortedBy { it.updateId }) {
                    botService.handle(update)
                    offset = update.updateId + 1
                }
            } catch (exception: Exception) {
                logger.log(Level.WARNING, "Polling failed, retrying in 5 seconds", exception)
                delay(5_000L)
            }
        }
    }
}
