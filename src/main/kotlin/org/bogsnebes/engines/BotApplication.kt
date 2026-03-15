package org.bogsnebes.engines

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
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
                val targetRepository = PingTargetRepository(config.databasePath)
                val allPingSessionRepository = AllPingSessionRepository(config.databasePath)
                try {
                    val botUser = bot.getMe().toTelegramUser()
                    val applicationScope = CoroutineScope(currentCoroutineContext())
                    val telegramGateway = TgBotApiGateway(bot)
                    val botService = BotService(
                        botUser = botUser,
                        telegramGateway = telegramGateway,
                        cooldownNoticeManager = CooldownNoticeManager(
                            telegramGateway = telegramGateway,
                            scope = applicationScope,
                            clock = clock,
                        ),
                        pingTargetRepository = targetRepository,
                        allPingSessionRepository = allPingSessionRepository,
                        cooldownTracker = PingCooldownTracker(config.cooldown),
                        clock = clock,
                    )

                    logger.info(
                        "Starting bot @${botUser.username ?: botUser.id} with database ${config.databasePath}"
                    )

                    val pollingRunner = TelegramLongPollingRunner(
                        updatesFetcher = TelegramGetUpdatesClient.fromHttpClient(
                            client = client,
                            botToken = config.botToken,
                            pollTimeoutSeconds = config.pollTimeoutSeconds,
                            allowedUpdates = listOf("message", "callback_query"),
                        ),
                        logger = logger,
                    )

                    try {
                        pollingRunner.run { update ->
                            val mappedUpdate = TelegramUpdateMapper.map(update) ?: return@run
                            runCatching {
                                botService.handle(mappedUpdate)
                            }.onFailure { error ->
                                logger.log(
                                    Level.WARNING,
                                    "Skipped Telegram update ${mappedUpdate.updateId} after handler failure",
                                    error,
                                )
                            }
                        }
                    } finally {
                        applicationScope.coroutineContext.cancelChildren()
                    }
                } finally {
                    allPingSessionRepository.close()
                    targetRepository.close()
                }
            } finally {
                bot.close()
            }
        } finally {
            client.close()
        }
    }
}
