package org.bogsnebes.engines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BotServiceTest {
    @Test
    fun `handles group command from configured tags and preserves thread id`() = runTest {
        val dbPath = Files.createTempFile("bot-service", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(repository, gateway, clock, backgroundScope)

                repository.replacePingTags(-100L, listOf("alice", "bob"))

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
                assertTrue(gateway.sentMessages.single().text.contains("@alice"))
                assertTrue(gateway.sentMessages.single().text.contains("@bob"))
                assertTrue(gateway.editedMessages.isEmpty())
                assertTrue(gateway.deletedMessages.isEmpty())
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `replies in private chat with group only message`() = runTest {
        val dbPath = Files.createTempFile("bot-service-private", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(repository, gateway, clock, backgroundScope)

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
                assertTrue(gateway.deletedMessages.isEmpty())
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `returns explicit error when tags are not configured`() = runTest {
        val dbPath = Files.createTempFile("bot-service-empty", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(repository, gateway, clock, backgroundScope)

                service.handle(
                    TelegramUpdate(
                        updateId = 3L,
                        message = TelegramMessage(
                            messageId = 8L,
                            date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
                            chat = TelegramChat(id = -100L, type = "supergroup"),
                            from = TelegramUser(id = 1L, isBot = false, firstName = "Bob"),
                            text = "/all",
                        ),
                    ),
                )

                assertEquals(
                    "Список тегов не настроен. Используйте /add @username...",
                    gateway.sentMessages.single().text,
                )
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `applies cooldown to repeated command and updates notice until expiry`() = runTest {
        val dbPath = Files.createTempFile("bot-service-cooldown", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(repository, gateway, clock, backgroundScope)

                repository.replacePingTags(-100L, listOf("alice"))

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
                assertEquals(listOf(8L), gateway.deletedMessages.map { it.messageId })

                advanceTimeBy(1_000L)
                runCurrent()

                assertEquals(1, gateway.editedMessages.size)
                assertEquals(2L, gateway.editedMessages.single().messageId)
                assertTrue(gateway.editedMessages.single().text.contains("9м 59с"))

                advanceTimeBy(Duration.ofMinutes(10).minusSeconds(1).toMillis())
                runCurrent()

                assertEquals(2L, gateway.deletedMessages.last().messageId)
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `replaces previous cooldown notice on a new command during cooldown`() = runTest {
        val dbPath = Files.createTempFile("bot-service-cooldown-replace", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(repository, gateway, clock, backgroundScope)

                repository.replacePingTags(-100L, listOf("alice"))

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
                service.handle(
                    update.copy(
                        updateId = 4L,
                        message = update.message?.copy(messageId = 9L),
                    ),
                )
                service.handle(
                    update.copy(
                        updateId = 5L,
                        message = update.message?.copy(messageId = 10L),
                    ),
                )

                assertEquals(3, gateway.sentMessages.size)
                assertTrue(gateway.sentMessages.last().text.contains("кулдауне"))
                assertEquals(listOf(9L, 2L, 10L), gateway.deletedMessages.map { it.messageId })
                assertEquals(3L, gateway.sentMessages.last().messageId)
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `allows admin to replace ping tags`() = runTest {
        val dbPath = Files.createTempFile("bot-service-add", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            MemberRepository(dbPath).use { repository ->
                repository.replacePingTags(-100L, listOf("old"))
                val gateway = FakeTelegramGateway().apply {
                    adminUsers += -100L to 1L
                }
                val service = createService(repository, gateway, clock, backgroundScope)

                service.handle(
                    TelegramUpdate(
                        updateId = 6L,
                        message = TelegramMessage(
                            messageId = 11L,
                            messageThreadId = 42L,
                            date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
                            chat = TelegramChat(id = -100L, type = "supergroup"),
                            from = TelegramUser(id = 1L, isBot = false, firstName = "Bob"),
                            text = "/add @Alice @bob @ALICE",
                        ),
                    ),
                )

                assertEquals(listOf("alice", "bob"), repository.listPingTags(-100L))
                assertEquals("Список тегов обновлён: @alice @bob", gateway.sentMessages.single().text)
                assertEquals(42L, gateway.sentMessages.single().messageThreadId)
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `rejects add command from non admin`() = runTest {
        val dbPath = Files.createTempFile("bot-service-add-denied", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(repository, gateway, clock, backgroundScope)

                service.handle(
                    TelegramUpdate(
                        updateId = 7L,
                        message = TelegramMessage(
                            messageId = 12L,
                            date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
                            chat = TelegramChat(id = -100L, type = "supergroup"),
                            from = TelegramUser(id = 1L, isBot = false, firstName = "Bob"),
                            text = "/add @alice",
                        ),
                    ),
                )

                assertEquals(
                    "Команда /add доступна только администраторам чата.",
                    gateway.sentMessages.single().text,
                )
                assertTrue(repository.listPingTags(-100L).isEmpty())
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `returns add usage for invalid add command from admin`() = runTest {
        val dbPath = Files.createTempFile("bot-service-add-invalid", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway().apply {
                    adminUsers += -100L to 1L
                }
                val service = createService(repository, gateway, clock, backgroundScope)

                service.handle(
                    TelegramUpdate(
                        updateId = 8L,
                        message = TelegramMessage(
                            messageId = 13L,
                            date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
                            chat = TelegramChat(id = -100L, type = "supergroup"),
                            from = TelegramUser(id = 1L, isBot = false, firstName = "Bob"),
                            text = "/add @alice bob",
                        ),
                    ),
                )

                assertEquals(
                    "Использование: /add @username1 @username2",
                    gateway.sentMessages.single().text,
                )
                assertTrue(repository.listPingTags(-100L).isEmpty())
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    private fun createService(
        repository: MemberRepository,
        gateway: FakeTelegramGateway,
        clock: SchedulerClock,
        scope: CoroutineScope,
    ): BotService = BotService(
        botUser = TelegramUser(id = 999L, isBot = true, firstName = "PingAll", username = "PingAllBot"),
        telegramGateway = gateway,
        cooldownNoticeManager = CooldownNoticeManager(
            telegramGateway = gateway,
            scope = scope,
            clock = clock,
        ),
        memberRepository = repository,
        cooldownTracker = PingCooldownTracker(Duration.ofMinutes(10)),
        activeWindow = Duration.ofDays(7),
        clock = clock,
    )
}
