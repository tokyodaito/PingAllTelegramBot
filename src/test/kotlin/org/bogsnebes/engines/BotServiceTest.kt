package org.bogsnebes.engines

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotServiceTest {
    @Test
    fun `handles group command and preserves thread id`() = runBlocking {
        val dbPath = Files.createTempFile("bot-service", ".db")

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = BotService(
                    botUser = TelegramUser(id = 999L, isBot = true, firstName = "PingAll", username = "PingAllBot"),
                    telegramGateway = gateway,
                    memberRepository = repository,
                    cooldownTracker = PingCooldownTracker(java.time.Duration.ofMinutes(10)),
                    activeWindow = java.time.Duration.ofDays(7),
                    clock = Clock.fixed(Instant.parse("2026-03-14T12:00:00Z"), ZoneOffset.UTC),
                )

                repository.upsertSeenMember(
                    chatId = -100L,
                    user = TelegramUser(id = 2L, isBot = false, firstName = "Alice"),
                    status = "member",
                    seenAt = Instant.parse("2026-03-14T11:00:00Z"),
                    source = "message",
                )

                service.handle(
                    TelegramUpdate(
                        updateId = 1L,
                        message = TelegramMessage(
                            messageId = 5L,
                            messageThreadId = 42L,
                            date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
                            chat = TelegramChat(id = -100L, type = "supergroup"),
                            from = TelegramUser(id = 1L, isBot = false, firstName = "Bob"),
                            text = "/all Подъем",
                        ),
                    )
                )

                assertEquals(1, gateway.sentMessages.size)
                assertEquals(42L, gateway.sentMessages.single().messageThreadId)
                assertTrue(gateway.sentMessages.single().text.contains("Подъем"))
                assertTrue(gateway.sentMessages.single().text.contains("tg://user?id=1"))
                assertTrue(gateway.sentMessages.single().text.contains("tg://user?id=2"))
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `replies in private chat with group only message`() = runBlocking {
        val dbPath = Files.createTempFile("bot-service-private", ".db")

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = BotService(
                    botUser = TelegramUser(id = 999L, isBot = true, firstName = "PingAll", username = "PingAllBot"),
                    telegramGateway = gateway,
                    memberRepository = repository,
                    cooldownTracker = PingCooldownTracker(java.time.Duration.ofMinutes(10)),
                    activeWindow = java.time.Duration.ofDays(7),
                    clock = Clock.fixed(Instant.parse("2026-03-14T12:00:00Z"), ZoneOffset.UTC),
                )

                service.handle(
                    TelegramUpdate(
                        updateId = 2L,
                        message = TelegramMessage(
                            messageId = 7L,
                            date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
                            chat = TelegramChat(id = 100L, type = "private"),
                            from = TelegramUser(id = 1L, isBot = false, firstName = "Bob"),
                            text = "/all",
                        ),
                    )
                )

                assertEquals(
                    "Команда /all работает только в группах и супергруппах.",
                    gateway.sentMessages.single().text,
                )
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `applies cooldown to repeated command`() = runBlocking {
        val dbPath = Files.createTempFile("bot-service-cooldown", ".db")

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = BotService(
                    botUser = TelegramUser(id = 999L, isBot = true, firstName = "PingAll", username = "PingAllBot"),
                    telegramGateway = gateway,
                    memberRepository = repository,
                    cooldownTracker = PingCooldownTracker(java.time.Duration.ofMinutes(10)),
                    activeWindow = java.time.Duration.ofDays(7),
                    clock = Clock.fixed(Instant.parse("2026-03-14T12:00:00Z"), ZoneOffset.UTC),
                )

                repository.upsertSeenMember(
                    chatId = -100L,
                    user = TelegramUser(id = 2L, isBot = false, firstName = "Alice"),
                    status = "member",
                    seenAt = Instant.parse("2026-03-14T11:00:00Z"),
                    source = "message",
                )

                val update = TelegramUpdate(
                    updateId = 3L,
                    message = TelegramMessage(
                        messageId = 8L,
                        date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
                        chat = TelegramChat(id = -100L, type = "supergroup"),
                        from = TelegramUser(id = 1L, isBot = false, firstName = "Bob"),
                        text = "/all",
                    ),
                )

                service.handle(update)
                service.handle(update.copy(updateId = 4L))

                assertEquals(2, gateway.sentMessages.size)
                assertTrue(gateway.sentMessages.last().text.contains("кулдауне"))
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }
}
