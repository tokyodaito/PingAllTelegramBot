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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BotServiceTest {
    @Test
    fun `handles group command from configured tags and preserves thread id`() = runTest {
        val dbPath = Files.createTempFile("bot-service", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice"), PingTagTarget.forUsername("bob")))

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
                    ),
                )

                assertEquals(1, gateway.sentMessages.size)
                val sentMessage = gateway.sentMessages.single()
                assertEquals(42L, sentMessage.messageThreadId)
                assertTrue(sentMessage.text.contains("Сбор ответа"))
                assertTrue(sentMessage.text.contains("Подъем"))
                assertTrue(sentMessage.text.contains("@alice - без ответа"))
                assertTrue(sentMessage.text.contains("@bob - без ответа"))
                assertEquals(listOf("Да", "Нет", "Думаю"), sentMessage.inlineKeyboard?.rows?.single()?.map(TelegramInlineButton::text))
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
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

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
                    ),
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
    fun `returns explicit error when tags are not configured`() = runTest {
        val dbPath = Files.createTempFile("bot-service-empty", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                service.handle(allCommandUpdate())

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
    fun `updates participant response from callback`() = runTest {
        val dbPath = Files.createTempFile("bot-service-callback", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice"), PingTagTarget.forUsername("bob")))
                service.handle(allCommandUpdate())

                val callbackData = gateway.sentMessages.single().inlineKeyboard
                    ?.rows
                    ?.single()
                    ?.first()
                    ?.callbackData
                    ?: error("callback data missing")

                service.handle(
                    TelegramUpdate(
                        updateId = 2L,
                        callbackQuery = callbackQuery(
                            id = "callback-1",
                            username = "alice",
                            data = callbackData,
                        ),
                    ),
                )

                assertEquals(1, gateway.editedMessages.size)
                assertTrue(gateway.editedMessages.single().text.contains("@alice - пойдет"))
                assertEquals("Ответ записан: пойдет", gateway.callbackAnswers.single().text)
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `rejects callback from user outside configured tags`() = runTest {
        val dbPath = Files.createTempFile("bot-service-callback-reject", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice")))
                service.handle(allCommandUpdate())

                val callbackData = gateway.sentMessages.single().inlineKeyboard
                    ?.rows
                    ?.single()
                    ?.first()
                    ?.callbackData
                    ?: error("callback data missing")

                service.handle(
                    TelegramUpdate(
                        updateId = 2L,
                        callbackQuery = callbackQuery(
                            id = "callback-1",
                            username = "bob",
                            data = callbackData,
                        ),
                    ),
                )

                assertTrue(gateway.editedMessages.isEmpty())
                assertEquals("Тебя нет в списке этого /all", gateway.callbackAnswers.single().text)
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `updates participant response from callback matched by user id`() = runTest {
        val dbPath = Files.createTempFile("bot-service-callback-user-id", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                repository.replaceTargets(
                    -100L,
                    listOf(
                        PingTagTarget(
                            identityKey = "u:77",
                            userId = 77L,
                            username = null,
                            displayNameSnapshot = "Sim",
                        ),
                    ),
                )
                service.handle(allCommandUpdate())

                val callbackData = gateway.sentMessages.single().inlineKeyboard
                    ?.rows
                    ?.single()
                    ?.first()
                    ?.callbackData
                    ?: error("callback data missing")

                service.handle(
                    TelegramUpdate(
                        updateId = 2L,
                        callbackQuery = callbackQuery(
                            id = "callback-1",
                            userId = 77L,
                            username = null,
                            firstName = "Sim",
                            data = callbackData,
                        ),
                    ),
                )

                assertEquals(1, gateway.editedMessages.size)
                assertTrue(gateway.editedMessages.single().text.contains("<a href=\"tg://user?id=77\">Sim</a> - пойдет"))
                assertEquals("Ответ записан: пойдет", gateway.callbackAnswers.single().text)
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
            PingTargetRepository(dbPath).use { repository ->
                AllPingSessionRepository(dbPath).use { sessionRepository ->
                    val gateway = FakeTelegramGateway()
                    val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                    repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice")))

                    val update = allCommandUpdate()
                    service.handle(update)
                    val firstSessionId = sessionRepository.findActiveSession(-100L)?.id
                    service.handle(update.copy(updateId = 4L))

                    assertEquals(2, gateway.sentMessages.size)
                    assertTrue(gateway.sentMessages.first().inlineKeyboard != null)
                    assertEquals(
                        "Команда /all сейчас на кулдауне. Попробуйте снова через 10м 0с.",
                        gateway.sentMessages.last().text,
                    )
                    assertEquals(firstSessionId, sessionRepository.findActiveSession(-100L)?.id)
                    assertTrue(gateway.removedInlineKeyboards.isEmpty())
                    assertEquals(listOf(8L), gateway.deletedMessages.map(DeletedMessage::messageId))

                    advanceTimeBy(10_000L)
                    runCurrent()

                    assertEquals(1, gateway.editedMessages.size)
                    assertEquals(2L, gateway.editedMessages.single().messageId)
                    assertEquals(
                        "Команда /all сейчас на кулдауне. Попробуйте снова через 9м 50с.",
                        gateway.editedMessages.single().text,
                    )
                }
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `keeps previous poll active until cooldown fully expires`() = runTest {
        val dbPath = Files.createTempFile("bot-service-cooldown-edge", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                AllPingSessionRepository(dbPath).use { sessionRepository ->
                    val gateway = FakeTelegramGateway()
                    val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                    repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice")))

                    service.handle(allCommandUpdate())
                    val firstSessionId = sessionRepository.findActiveSession(-100L)?.id

                    advanceTimeBy(Duration.ofMinutes(10).minusSeconds(1).toMillis())
                    service.handle(allCommandUpdate(updateId = 2L, messageId = 9L))

                    assertEquals(firstSessionId, sessionRepository.findActiveSession(-100L)?.id)
                    assertTrue(gateway.removedInlineKeyboards.isEmpty())
                    assertEquals(2, gateway.sentMessages.size)
                    assertTrue(gateway.sentMessages.first().inlineKeyboard != null)
                    assertEquals(null, gateway.sentMessages.last().inlineKeyboard)
                    assertTrue(gateway.sentMessages.last().text.contains("Команда /all сейчас на кулдауне"))
                }
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `blocks repeated command from replacing active poll after tracker state loss`() = runTest {
        val dbPath = Files.createTempFile("bot-service-cooldown-reload", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                AllPingSessionRepository(dbPath).use { sessionRepository ->
                    val firstGateway = FakeTelegramGateway()
                    val firstService = createService(dbPath, repository, firstGateway, clock, backgroundScope)

                    repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice")))

                    firstService.handle(allCommandUpdate())
                    val firstSessionId = sessionRepository.findActiveSession(-100L)?.id

                    advanceTimeBy(Duration.ofMinutes(5).toMillis())

                    val secondGateway = FakeTelegramGateway()
                    val secondService = createService(dbPath, repository, secondGateway, clock, backgroundScope)
                    secondService.handle(allCommandUpdate(updateId = 2L, messageId = 9L))

                    assertEquals(firstSessionId, sessionRepository.findActiveSession(-100L)?.id)
                    assertTrue(secondGateway.removedInlineKeyboards.isEmpty())
                    assertEquals(1, secondGateway.sentMessages.size)
                    assertEquals(null, secondGateway.sentMessages.single().inlineKeyboard)
                    assertTrue(secondGateway.sentMessages.single().text.contains("Команда /all сейчас на кулдауне"))
                }
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `recovers from partial publish failure and allows immediate retry`() = runTest {
        val dbPath = Files.createTempFile("bot-service-partial-failure", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                AllPingSessionRepository(dbPath).use { sessionRepository ->
                    val gateway = FakeTelegramGateway().apply {
                        sendFailures[2] = IllegalStateException("telegram send failed")
                    }
                    val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                    repository.replaceTargets(
                        -100L,
                        listOf(
                            PingTagTarget.forUsername("alice"),
                            PingTagTarget.forUsername("bob"),
                            PingTagTarget.forUsername("charlie"),
                        ),
                    )

                    service.handle(
                        allCommandUpdate(
                            announcement = "X".repeat(4_090),
                        ),
                    )

                    assertEquals(2, gateway.sentMessages.size)
                    assertTrue(gateway.sentMessages.last().text.contains("Не удалось отправить /all"))
                    assertEquals(listOf(1L), gateway.deletedMessages.map(DeletedMessage::messageId))
                    assertNull(sessionRepository.findActiveSession(-100L))

                    gateway.sendFailures.clear()
                    service.handle(allCommandUpdate(updateId = 2L, messageId = 9L, announcement = "Повтор"))

                    assertTrue(gateway.sentMessages.last().text.contains("Повтор"))
                    assertNotNull(sessionRepository.findActiveSession(-100L))
                }
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `returns publishing notice for pending session callback`() = runTest {
        val dbPath = Files.createTempFile("bot-service-pending-callback", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                val sessionRepository = AllPingSessionRepository(dbPath)
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                val pendingSession = sessionRepository.createPendingSession(
                    chatId = -100L,
                    messageThreadId = null,
                    announcement = "Подъем",
                    targets = listOf(PingTagTarget.forUsername("alice")),
                    createdAt = Instant.parse("2026-03-14T12:00:00Z"),
                )

                service.handle(
                    TelegramUpdate(
                        updateId = 2L,
                        callbackQuery = callbackQuery(
                            id = "callback-pending",
                            username = "alice",
                            data = AllPingCallbackData.encode(pendingSession.id, AllPingResponse.YES),
                        ),
                    ),
                )

                assertEquals("Сбор еще публикуется", gateway.callbackAnswers.single().text)
                sessionRepository.close()
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `callback refresh failure closes session and returns fallback answer`() = runTest {
        val dbPath = Files.createTempFile("bot-service-refresh-failure", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                AllPingSessionRepository(dbPath).use { sessionRepository ->
                    val gateway = FakeTelegramGateway().apply {
                        editFailures += IllegalStateException("edit failed")
                    }
                    val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                    repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice")))
                    service.handle(allCommandUpdate())

                    val callbackData = gateway.sentMessages.single().inlineKeyboard
                        ?.rows
                        ?.single()
                        ?.first()
                        ?.callbackData
                        ?: error("callback data missing")

                    service.handle(
                        TelegramUpdate(
                            updateId = 2L,
                            callbackQuery = callbackQuery(
                                id = "callback-1",
                                username = "alice",
                                data = callbackData,
                            ),
                        ),
                    )

                    assertEquals("Ответ записан, но список временно недоступен", gateway.callbackAnswers.single().text)
                    assertEquals(AllPingSessionStatus.CLOSED, sessionRepository.findSession(1L)?.status)
                    assertEquals(listOf(1L), gateway.removedInlineKeyboards.map(RemovedInlineKeyboard::messageId))
                }
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `keeps previous interactive session when next publish fails`() = runTest {
        val dbPath = Files.createTempFile("bot-service-keep-previous", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                AllPingSessionRepository(dbPath).use { sessionRepository ->
                    val gateway = FakeTelegramGateway()
                    val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                    repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice")))
                    service.handle(allCommandUpdate())
                    val firstSessionId = sessionRepository.findActiveSession(-100L)?.id

                    advanceTimeBy(Duration.ofMinutes(10).toMillis())
                    gateway.sendFailures[3] = IllegalStateException("second publish failed")
                    service.handle(
                        allCommandUpdate(
                            updateId = 2L,
                            messageId = 9L,
                            announcement = "X".repeat(4_090),
                        ),
                    )

                    assertEquals(firstSessionId, sessionRepository.findActiveSession(-100L)?.id)
                    assertTrue(gateway.removedInlineKeyboards.isEmpty())
                }
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `treats forum topics as one chat scope for active session`() = runTest {
        val dbPath = Files.createTempFile("bot-service-topics", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("alice")))

                service.handle(allCommandUpdate(messageId = 8L, messageThreadId = 42L))
                advanceTimeBy(Duration.ofMinutes(10).toMillis())
                service.handle(allCommandUpdate(updateId = 2L, messageId = 9L, messageThreadId = 99L, announcement = "Вторая тема"))

                assertEquals(listOf(1L), gateway.removedInlineKeyboards.map(RemovedInlineKeyboard::messageId))
                assertEquals(99L, gateway.sentMessages.last().messageThreadId)
                assertTrue(gateway.sentMessages.last().text.contains("Вторая тема"))
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
            PingTargetRepository(dbPath).use { repository ->
                repository.replaceTargets(-100L, listOf(PingTagTarget.forUsername("old")))
                val gateway = FakeTelegramGateway().apply {
                    adminUsers += -100L to 1L
                }
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

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

                assertEquals(
                    listOf(PingTagTarget.forUsername("alice"), PingTagTarget.forUsername("bob")),
                    repository.listTargets(-100L),
                )
                assertEquals("Список тегов обновлён: @alice @bob", gateway.sentMessages.single().text)
                assertEquals(42L, gateway.sentMessages.single().messageThreadId)
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `allows admin to replace ping tags with text mention target`() = runTest {
        val dbPath = Files.createTempFile("bot-service-add-text-mention", ".db")
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-03-14T12:00:00Z"))

        try {
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway().apply {
                    adminUsers += -100L to 1L
                }
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

                service.handle(
                    TelegramUpdate(
                        updateId = 9L,
                        message = TelegramMessage(
                            messageId = 14L,
                            date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
                            chat = TelegramChat(id = -100L, type = "supergroup"),
                            from = TelegramUser(id = 1L, isBot = false, firstName = "Bob"),
                            text = "/add Sim",
                            textSources = listOf(
                                TelegramRegularTextSource("/add "),
                                TelegramTextMentionTextSource(
                                    user = TelegramUser(id = 77L, isBot = false, firstName = "Sim"),
                                    source = "Sim",
                                ),
                            ),
                        ),
                    ),
                )

                assertEquals(
                    listOf(
                        PingTagTarget(
                            identityKey = "u:77",
                            userId = 77L,
                            username = null,
                            displayNameSnapshot = "Sim",
                        ),
                    ),
                    repository.listTargets(-100L),
                )
                assertEquals(
                    "Список тегов обновлён: <a href=\"tg://user?id=77\">Sim</a>",
                    gateway.sentMessages.single().text,
                )
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
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

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
                assertTrue(repository.listTargets(-100L).isEmpty())
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
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway().apply {
                    adminUsers += -100L to 1L
                }
                val service = createService(dbPath, repository, gateway, clock, backgroundScope)

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
                assertTrue(repository.listTargets(-100L).isEmpty())
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    private fun createService(
        dbPath: java.nio.file.Path,
        repository: PingTargetRepository,
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
        pingTargetRepository = repository,
        allPingSessionRepository = AllPingSessionRepository(dbPath),
        cooldownTracker = PingCooldownTracker(Duration.ofMinutes(10)),
        clock = clock,
    )

    private fun allCommandUpdate(
        updateId: Long = 1L,
        messageId: Long = 8L,
        messageThreadId: Long? = null,
        announcement: String? = null,
    ): TelegramUpdate = TelegramUpdate(
        updateId = updateId,
        message = TelegramMessage(
            messageId = messageId,
            messageThreadId = messageThreadId,
            date = Instant.parse("2026-03-14T12:00:00Z").epochSecond,
            chat = TelegramChat(id = -100L, type = "supergroup"),
            from = TelegramUser(id = 1L, isBot = false, firstName = "Bob", username = "bob"),
            text = listOfNotNull("/all", announcement).joinToString(" "),
        ),
    )

    private fun callbackQuery(
        id: String,
        username: String,
        data: String,
        messageId: Long = 1L,
    ): TelegramCallbackQuery = callbackQuery(
        id = id,
        userId = 100L + username.length,
        username = username,
        firstName = username,
        data = data,
        messageId = messageId,
    )

    private fun callbackQuery(
        id: String,
        userId: Long,
        username: String?,
        firstName: String,
        data: String,
        messageId: Long = 1L,
    ): TelegramCallbackQuery = TelegramCallbackQuery(
        id = id,
        from = TelegramUser(id = userId, isBot = false, firstName = firstName, username = username),
        data = data,
        chat = TelegramChat(id = -100L, type = "supergroup"),
        messageId = messageId,
    )
}
