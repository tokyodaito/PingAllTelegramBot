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
    fun `maps message update with new chat members`() {
        val update = assertNotNull(
            TelegramUpdateMapper.map(
                decodeLibraryUpdate(
                    """
                    {
                      "update_id": 1001,
                      "message": {
                        "message_id": 10,
                        "date": 1773489600,
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
                        "new_chat_members": [
                          {
                            "id": 11,
                            "is_bot": false,
                            "first_name": "New",
                            "last_name": "User",
                            "username": "new_user"
                          }
                        ]
                      }
                    }
                    """,
                ),
            ),
        )

        val message = assertNotNull(update.message)
        assertEquals(1001L, update.updateId)
        assertEquals(10L, message.messageId)
        assertEquals(-200L, message.chat.id)
        assertEquals("supergroup", message.chat.type)
        assertEquals("owner", message.from?.username)
        assertEquals(1, message.newChatMembers.size)
        assertEquals(11L, message.newChatMembers.single().id)
        assertEquals("new_user", message.newChatMembers.single().username)
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
    fun `maps chat member updates and dispatches mapped command to bot service`() = runTest {
        val dbPath = Files.createTempFile("dispatch", ".db")

        try {
            MemberRepository(dbPath).use { repository ->
                val gateway = FakeTelegramGateway()
                val service = BotService(
                    botUser = TelegramUser(id = 999L, isBot = true, firstName = "PingAll", username = "PingAllBot"),
                    telegramGateway = gateway,
                    cooldownNoticeManager = CooldownNoticeManager(
                        telegramGateway = gateway,
                        scope = backgroundScope,
                        clock = Clock.fixed(Instant.parse("2026-03-14T12:00:00Z"), ZoneOffset.UTC),
                    ),
                    memberRepository = repository,
                    allPingSessionRepository = AllPingSessionRepository(dbPath),
                    cooldownTracker = PingCooldownTracker(java.time.Duration.ofMinutes(10)),
                    activeWindow = java.time.Duration.ofDays(7),
                    clock = Clock.fixed(Instant.parse("2026-03-14T12:00:00Z"), ZoneOffset.UTC),
                )

                val newMembersUpdate = assertNotNull(
                    TelegramUpdateMapper.map(
                        decodeLibraryUpdate(
                            """
                            {
                              "update_id": 1001,
                              "message": {
                                "message_id": 10,
                                "date": 1773489600,
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
                                "new_chat_members": [
                                  {
                                    "id": 11,
                                    "is_bot": false,
                                    "first_name": "New",
                                    "last_name": "User",
                                    "username": "new_user"
                                  }
                                ]
                              }
                            }
                            """,
                        ),
                    ),
                )

                val memberUpdate = assertNotNull(
                    TelegramUpdateMapper.map(
                        decodeLibraryUpdate(
                            """
                            {
                              "update_id": 1002,
                              "chat_member": {
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
                                "date": 1773489660,
                                "old_chat_member": {
                                  "user": {
                                    "id": 11,
                                    "is_bot": false,
                                    "first_name": "New",
                                    "last_name": "User",
                                    "username": "new_user"
                                  },
                                  "status": "member"
                                },
                                "new_chat_member": {
                                  "user": {
                                    "id": 11,
                                    "is_bot": false,
                                    "first_name": "New",
                                    "last_name": "User",
                                    "username": "new_user"
                                  },
                                  "status": "administrator"
                                }
                              }
                            }
                            """,
                        ),
                    ),
                )

                val libraryCommandUpdate = decodeLibraryUpdate(
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
                )

                val commandUpdate = assertNotNull(TelegramUpdateMapper.map(libraryCommandUpdate))

                service.handle(newMembersUpdate)
                service.handle(memberUpdate)
                repository.replacePingTags(-200L, listOf("new_user"))
                service.handle(commandUpdate)

                val members = repository.listMentionableMembers(
                    chatId = -200,
                    activeSince = Instant.parse("2026-03-01T00:00:00Z"),
                )

                assertEquals(2, members.size)
                assertEquals("administrator", members.first { it.userId == 11L }.status)
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
