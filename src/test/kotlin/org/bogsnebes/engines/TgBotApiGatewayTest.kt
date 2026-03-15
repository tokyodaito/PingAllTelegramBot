package org.bogsnebes.engines

import dev.inmo.tgbotapi.requests.DeleteMessage
import dev.inmo.tgbotapi.requests.edit.text.EditChatMessageText
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.IdChatIdentifier
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.message.HTML
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.update.MessageUpdate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TgBotApiGatewayTest {
    @Test
    fun `sends html message with thread id and disabled preview`() = runBlocking {
        val executor = RecordingRequestsExecutor(
            responses = mapOf(
                "sendMessage" to sampleSentTextMessage(),
            ),
        )
        val gateway = TgBotApiGateway(executor)

        val sentMessage = gateway.sendMessage(
            chatId = -200L,
            text = """<a href="tg://user?id=1">User</a>""",
            messageThreadId = 77L,
        )

        val request = assertIs<SendTextMessage>(executor.requests.single())
        val chatId = assertIs<IdChatIdentifier>(request.chatId)

        assertEquals(-200L, chatId.chatId.long)
        assertEquals("""<a href="tg://user?id=1">User</a>""", request.text)
        assertEquals(77L, request.threadId?.long)
        assertEquals(HTML, request.parseMode)
        assertEquals(LinkPreviewOptions.Disabled, request.linkPreviewOptions)
        assertEquals(-200L, sentMessage.chatId)
        assertEquals(99L, sentMessage.messageId)
        assertEquals(77L, sentMessage.messageThreadId)
    }

    @Test
    fun `edits html message with disabled preview`() = runBlocking {
        val executor = RecordingRequestsExecutor(
            responses = mapOf(
                "editMessageText" to sampleSentTextMessage(),
            ),
        )
        val gateway = TgBotApiGateway(executor)

        gateway.editMessageText(
            chatId = -200L,
            messageId = 99L,
            text = "Updated",
        )

        val request = assertIs<EditChatMessageText>(executor.requests.single())
        val chatId = assertIs<IdChatIdentifier>(request.chatId)

        assertEquals(-200L, chatId.chatId.long)
        assertEquals(99L, request.messageId.long)
        assertEquals("Updated", request.text)
        assertEquals(HTML, request.parseMode)
        assertEquals(LinkPreviewOptions.Disabled, request.linkPreviewOptions)
    }

    @Test
    fun `deletes chat message`() = runBlocking {
        val executor = RecordingRequestsExecutor(
            responses = mapOf(
                "deleteMessage" to Unit,
            ),
        )
        val gateway = TgBotApiGateway(executor)

        gateway.deleteMessage(
            chatId = -200L,
            messageId = 99L,
        )

        val request = assertIs<DeleteMessage>(executor.requests.single())
        val chatId = assertIs<IdChatIdentifier>(request.chatId)

        assertEquals(-200L, chatId.chatId.long)
        assertEquals(99L, request.messageId.long)
    }

    private fun sampleSentTextMessage(): ContentMessage<TextContent> {
        val update = decodeLibraryUpdate(
            """
            {
              "update_id": 1,
              "message": {
                "message_id": 99,
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
                "text": "ok"
              }
            }
            """,
        )

        @Suppress("UNCHECKED_CAST")
        return (update as MessageUpdate).data as ContentMessage<TextContent>
    }
}
