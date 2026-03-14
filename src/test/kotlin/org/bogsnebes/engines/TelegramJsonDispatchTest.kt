package org.bogsnebes.engines

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramJsonDispatchTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `dispatches message and chat member payloads`() = runBlocking {
        val dbPath = Files.createTempFile("dispatch", ".db")

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

                val newMembersUpdate = json.decodeFromString<TelegramUpdate>(
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
                    """.trimIndent()
                )

                val memberUpdate = json.decodeFromString<TelegramUpdate>(
                    """
                    {
                      "update_id": 1002,
                      "chat_member": {
                        "chat": {
                          "id": -200,
                          "type": "supergroup",
                          "title": "Team"
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
                    """.trimIndent()
                )

                service.handle(newMembersUpdate)
                service.handle(memberUpdate)

                val members = repository.listMentionableMembers(
                    chatId = -200,
                    activeSince = Instant.parse("2026-03-01T00:00:00Z"),
                )

                assertEquals(2, members.size)
                assertEquals("administrator", members.first { it.userId == 11L }.status)

                val commandUpdate = json.decodeFromString<TelegramUpdate>(
                    """
                    {
                      "update_id": 1003,
                      "message": {
                        "message_id": 11,
                        "message_thread_id": 77,
                        "date": 1773489720,
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
                        "text": "/all Wake up"
                      }
                    }
                    """.trimIndent()
                )

                service.handle(commandUpdate)

                assertEquals(1, gateway.sentMessages.size)
                assertEquals(77L, gateway.sentMessages.single().messageThreadId)
                assertTrue(gateway.sentMessages.single().text.contains("Wake up"))
                assertTrue(gateway.sentMessages.single().text.contains("tg://user?id=11"))
            }
        } finally {
            dbPath.deleteIfExists()
        }
    }

    @Test
    fun `serializes parse mode for send message request`() {
        val payload = json.encodeToString<SendMessageRequest>(
            SendMessageRequest(
                chatId = -200,
                text = """<a href="tg://user?id=1">User</a>""",
                messageThreadId = 77L,
            )
        )

        assertTrue(payload.contains(""""parse_mode":"HTML""""))
        assertTrue(payload.contains(""""message_thread_id":77"""))
    }
}
