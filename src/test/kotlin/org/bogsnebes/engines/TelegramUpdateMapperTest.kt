package org.bogsnebes.engines

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramUpdateMapperTest {
    @Test
    fun `maps text mention entities into message text sources`() {
        val update = assertNotNull(
            TelegramUpdateMapper.map(
                decodeLibraryUpdate(
                    """
                    {
                      "update_id": 1005,
                      "message": {
                        "message_id": 12,
                        "date": 1773489800,
                        "chat": {
                          "id": -200,
                          "type": "supergroup",
                          "title": "Team"
                        },
                        "from": {
                          "id": 10,
                          "is_bot": false,
                          "first_name": "Owner",
                          "username": "owner"
                        },
                        "text": "/add Sim",
                        "entities": [
                          {
                            "offset": 0,
                            "length": 4,
                            "type": "bot_command"
                          },
                          {
                            "offset": 5,
                            "length": 3,
                            "type": "text_mention",
                            "user": {
                              "id": 77,
                              "is_bot": false,
                              "first_name": "Sim"
                            }
                          }
                        ]
                      }
                    }
                    """,
                ),
            ),
        )

        val message = assertNotNull(update.message)
        assertEquals(
            listOf(
                TelegramRegularTextSource("/add"),
                TelegramRegularTextSource(" "),
                TelegramTextMentionTextSource(
                    user = TelegramUser(id = 77L, isBot = false, firstName = "Sim"),
                    source = "Sim",
                ),
            ),
            message.textSources,
        )
    }

    @Test
    fun `maps callback query update from message button`() {
        val update = assertNotNull(
            TelegramUpdateMapper.map(
                decodeLibraryUpdate(
                    """
                    {
                      "update_id": 1004,
                      "callback_query": {
                        "id": "callback-1",
                        "from": {
                          "id": 10,
                          "is_bot": false,
                          "first_name": "Owner",
                          "username": "owner"
                        },
                        "chat_instance": "chat-instance",
                        "message": {
                          "message_id": 11,
                          "message_thread_id": 77,
                          "is_topic_message": true,
                          "date": 1773489720,
                          "chat": {
                            "id": -200,
                            "type": "supergroup",
                            "title": "Team"
                          },
                          "from": {
                            "id": 99,
                            "is_bot": true,
                            "first_name": "PingAll",
                            "username": "pingallbot"
                          },
                          "text": "Сбор ответа"
                        },
                        "data": "all:1:yes"
                      }
                    }
                    """,
                ),
            ),
        )

        val callbackQuery = assertNotNull(update.callbackQuery)
        assertEquals(1004L, update.updateId)
        assertEquals("callback-1", callbackQuery.id)
        assertEquals("owner", callbackQuery.from.username)
        assertEquals("all:1:yes", callbackQuery.data)
        assertEquals(-200L, callbackQuery.chat.id)
        assertEquals(11L, callbackQuery.messageId)
        assertNull(callbackQuery.messageThreadId)
        assertNull(update.message)
    }

    @Test
    fun `maps topic command update and dispatches thread id to bot service`() = runTest {
        val dbPath = Files.createTempFile("dispatch", ".db")

        try {
            PingTargetRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = BotService(
                    botUser = TelegramUser(id = 999L, isBot = true, firstName = "PingAll", username = "PingAllBot"),
                    telegramGateway = gateway,
                    cooldownNoticeManager = CooldownNoticeManager(
                        telegramGateway = gateway,
                        scope = backgroundScope,
                        clock = Clock.fixed(Instant.parse("2026-03-14T12:00:00Z"), ZoneOffset.UTC),
                    ),
                    pingTargetRepository = repository,
                    allPingSessionRepository = AllPingSessionRepository(dbPath),
                    cooldownTracker = PingCooldownTracker(java.time.Duration.ofMinutes(10)),
                    clock = Clock.fixed(Instant.parse("2026-03-14T12:00:00Z"), ZoneOffset.UTC),
                )

                repository.replaceTargets(-200L, listOf(PingTagTarget.forUsername("new_user")))

                val commandUpdate = assertNotNull(
                    TelegramUpdateMapper.map(
                        decodeLibraryUpdate(
                            """
                            {
                              "update_id": 1003,
                              "message": {
                                "message_id": 11,
                                "message_thread_id": 77,
                                "is_topic_message": true,
                                "date": 1773489720,
                                "chat": {
                                  "id": -200,
                                  "type": "supergroup",
                                  "is_forum": true,
                                  "title": "Team"
                                },
                                "from": {
                                  "id": 10,
                                  "is_bot": false,
                                  "first_name": "Owner",
                                  "username": "owner"
                                },
                                "text": "/all Wake up"
                              }
                            }
                            """,
                        ),
                    ),
                )

                service.handle(commandUpdate)

                assertEquals(1, gateway.sentMessages.size)
                assertEquals(77L, gateway.sentMessages.single().messageThreadId)
                assertTrue(gateway.sentMessages.single().text.contains("Wake up"))
                assertTrue(gateway.sentMessages.single().text.contains("@new_user"))
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }
}
