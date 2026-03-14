package org.bogsnebes.engines

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    BotApplication(BotConfig.fromEnvironment()).run()
}
