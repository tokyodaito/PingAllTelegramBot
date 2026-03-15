package org.bogsnebes.engines

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.utils.updates.retrieving.startGettingOfUpdatesByLongPolling
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import java.time.Clock
import java.util.logging.Level
import java.util.logging.Logger

class BotApplication(
    private val config: BotConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = Logger.getLogger(BotApplication::class.java.name)

    suspend fun run() {
        val timeoutMillis = (config.pollTimeoutSeconds + 10L) * 1_000L
        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = timeoutMillis
            }
        }

        try {
            val bot = telegramBot(config.botToken) {
                this.client = client
            }

            try {
                val repository = MemberRepository(config.databasePath)
                try {
                    val botUser = bot.getMe().toTelegramUser()
                    val botService = BotService(
                        botUser = botUser,
                        telegramGateway = TgBotApiGateway(bot),
                        memberRepository = repository,
                        cooldownTracker = PingCooldownTracker(config.cooldown),
                        activeWindow = config.activeWindow,
                        clock = clock,
                    )

                    logger.info(
                        "Starting bot @${botUser.username ?: botUser.id} with database ${config.databasePath}"
                    )

                    val pollingJob = bot.startGettingOfUpdatesByLongPolling(
                        timeoutSeconds = config.pollTimeoutSeconds,
                        scope = CoroutineScope(currentCoroutineContext()),
                        exceptionsHandler = { throwable: Throwable ->
                            logger.log(Level.WARNING, "Polling failed, retrying in 5 seconds", throwable)
                            delay(5_000L)
                        },
                        allowedUpdates = listOf("message", "chat_member"),
                    ) { update ->
                        TelegramUpdateMapper.map(update)?.let { botService.handle(it) }
                    }

                    pollingJob.join()
                } finally {
                    repository.close()
                }
            } finally {
                bot.close()
            }
        } finally {
            client.close()
        }
    }
}
