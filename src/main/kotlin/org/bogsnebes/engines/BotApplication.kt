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
                        memberRepository = repository,
                        cooldownTracker = PingCooldownTracker(config.cooldown),
                        activeWindow = config.activeWindow,
                        clock = clock,
                    )

                    logger.info(
                        "Starting bot @${botUser.username ?: botUser.id} with database ${config.databasePath}"
                    )

                    val allowedUpdates = listOf("message", "chat_member")
                    val pollingRunner = TelegramLongPollingRunner(
                        updatesFetcher = TelegramGetUpdatesClient.fromHttpClient(
                            client = client,
                            botToken = config.botToken,
                            pollTimeoutSeconds = config.pollTimeoutSeconds,
                            allowedUpdates = allowedUpdates,
                        ),
                        logger = logger,
                    )

                    try {
                        pollingRunner.run { update ->
                            TelegramUpdateMapper.map(update)?.let { botService.handle(it) }
                        }
                    } finally {
                        applicationScope.coroutineContext.cancelChildren()
                    }
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
