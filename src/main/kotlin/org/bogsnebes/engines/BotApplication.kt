package org.bogsnebes.engines

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import java.time.Clock
import java.util.logging.Logger

class BotApplication(
    private val config: BotConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = Logger.getLogger(BotApplication::class.java.name)

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun run() {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = (config.pollTimeoutSeconds + 10L) * 1_000L
                connectTimeoutMillis = 15_000L
                socketTimeoutMillis = (config.pollTimeoutSeconds + 10L) * 1_000L
            }
        }.use { client ->
            MemberRepository(config.databasePath).use { repository ->
                val api = TelegramApi(client, config.botToken)
                val botUser = api.getMe()
                val botService = BotService(
                    botUser = botUser,
                    telegramGateway = api,
                    memberRepository = repository,
                    cooldownTracker = PingCooldownTracker(config.cooldown),
                    activeWindow = config.activeWindow,
                    clock = clock,
                )

                logger.info(
                    "Starting bot @${botUser.username ?: botUser.id} with database ${config.databasePath}"
                )

                PollingRunner(
                    api = api,
                    botService = botService,
                    pollTimeoutSeconds = config.pollTimeoutSeconds,
                ).runForever()
            }
        }
    }
}
