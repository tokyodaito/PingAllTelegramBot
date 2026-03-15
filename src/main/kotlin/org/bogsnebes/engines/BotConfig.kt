package org.bogsnebes.engines

import java.nio.file.Path
import java.nio.file.Paths

data class BotConfig(
    val botToken: String,
    val databasePath: Path,
    val pollTimeoutSeconds: Int = 30,
    val cooldown: java.time.Duration = java.time.Duration.ofMinutes(10),
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BotConfig {
            val token = environment["BOT_TOKEN"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("BOT_TOKEN is required")

            val databasePath = Paths.get(environment["BOT_DB_PATH"]?.trim().orEmpty().ifEmpty { "./data/bot.db" })

            return BotConfig(
                botToken = token,
                databasePath = databasePath,
            )
        }
    }
}
