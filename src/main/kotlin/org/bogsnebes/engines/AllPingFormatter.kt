package org.bogsnebes.engines

object AllPingFormatter {
    private const val maxMessageLength = 4_096
    private const val defaultTitle = "Сбор ответа"
    private const val noResponseText = "без ответа"

    fun buildKeyboard(sessionId: Long): TelegramInlineKeyboard = TelegramInlineKeyboard(
        rows = listOf(
            AllPingResponse.entries.map { response ->
                TelegramInlineButton(
                    text = response.buttonText,
                    callbackData = AllPingCallbackData.encode(sessionId, response),
                )
            },
        ),
    )

    fun prepareChunkIndexes(usernames: List<String>, announcement: String?): List<Int> {
        val chunkIndexes = mutableListOf<Int>()
        val header = buildHeader(announcement)
        var currentChunkIndex = 0
        var currentChunkLength = header.length
        var chunkHasLines = false

        usernames.forEach { username ->
            val line = buildLine(username, null)
            val separatorLength = when {
                currentChunkIndex == 0 && !chunkHasLines -> "\n\n".length
                chunkHasLines -> "\n".length
                else -> 0
            }

            if (chunkHasLines && currentChunkLength + separatorLength + line.length > maxMessageLength) {
                currentChunkIndex += 1
                currentChunkLength = 0
                chunkHasLines = false
            }

            val normalizedSeparatorLength = when {
                currentChunkIndex == 0 && !chunkHasLines -> "\n\n".length
                chunkHasLines -> "\n".length
                else -> 0
            }

            currentChunkLength += normalizedSeparatorLength + line.length
            chunkHasLines = true
            chunkIndexes += currentChunkIndex
        }

        return chunkIndexes
    }

    fun buildMessageChunks(session: AllPingSession): List<String> {
        val participantsByChunk = session.participants
            .sortedBy(AllPingParticipant::position)
            .groupBy(AllPingParticipant::chunkIndex)

        val header = buildHeader(session.announcement)
        val lastChunkIndex = session.participants.maxOfOrNull(AllPingParticipant::chunkIndex) ?: 0

        return (0..lastChunkIndex).map { chunkIndex ->
            val builder = StringBuilder()
            val participants = participantsByChunk[chunkIndex].orEmpty()

            if (chunkIndex == 0) {
                builder.append(header)
            }

            participants.forEachIndexed { index, participant ->
                when {
                    chunkIndex == 0 && index == 0 -> builder.append("\n\n")
                    index > 0 -> builder.append('\n')
                }
                builder.append(buildLine(participant.username, participant.response))
            }

            builder.toString()
        }
    }

    private fun buildHeader(announcement: String?): String {
        val normalizedAnnouncement = announcement
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(MentionFormatter::escapeHtml)

        return if (normalizedAnnouncement == null) {
            defaultTitle
        } else {
            "$defaultTitle\n$normalizedAnnouncement"
        }
    }

    private fun buildLine(username: String, response: AllPingResponse?): String {
        val status = response?.statusText ?: noResponseText
        return "@${MentionFormatter.escapeHtml(username)} - $status"
    }
}
