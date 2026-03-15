package org.bogsnebes.engines

object AllPingFormatter {
    private const val maxMessageLength = 4_096
    private const val defaultTitle = "Сбор ответа"
    private const val noResponseText = "без ответа ⏳"
    private const val agreedCounterLabel = "Согласились"

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

    fun prepareChunkIndexes(targets: List<PingTagTarget>, announcement: String?): List<Int> {
        val chunkIndexes = mutableListOf<Int>()
        val header = buildHeader(announcement)
        val footerLength = buildCounterLine(agreedCount = targets.size, totalCount = targets.size).length + "\n\n".length
        var currentChunkIndex = 0
        var currentChunkLength = header.length
        var chunkHasLines = false

        targets.forEach { target ->
            val line = buildLine(target.userId, target.username, target.displayNameSnapshot, null)
            validateLineLength(line)

            val separatorLength = when {
                currentChunkIndex == 0 && !chunkHasLines -> "\n\n".length
                chunkHasLines -> "\n".length
                else -> 0
            }

            if (currentChunkLength + separatorLength + line.length + footerLength > maxMessageLength) {
                currentChunkIndex += 1
                currentChunkLength = 0
                chunkHasLines = false
            }

            val normalizedSeparatorLength = when {
                currentChunkIndex == 0 && !chunkHasLines -> "\n\n".length
                chunkHasLines -> "\n".length
                else -> 0
            }

            val nextChunkLength = currentChunkLength + normalizedSeparatorLength + line.length
            if (nextChunkLength + footerLength > maxMessageLength) {
                throw AllPingFormattingException("Participant line exceeds Telegram message limit with counter footer")
            }

            currentChunkLength = nextChunkLength
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

        val chunks = (0..lastChunkIndex).map { chunkIndex ->
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

                val line = buildLine(
                    userId = participant.userId,
                    username = participant.username,
                    displayNameSnapshot = participant.displayNameSnapshot,
                    response = participant.response,
                )
                validateLineLength(line)
                builder.append(line)
            }

            if (chunkIndex == lastChunkIndex) {
                if (builder.isNotEmpty()) {
                    builder.append("\n\n")
                }
                builder.append(
                    buildCounterLine(
                        agreedCount = session.participants.count { it.response == AllPingResponse.YES },
                        totalCount = session.participants.size,
                    ),
                )
            }

            builder.toString()
        }

        return validateChunkLengths(chunks)
    }

    private fun buildHeader(announcement: String?): String {
        val normalizedAnnouncement = announcement
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(MentionFormatter::escapeHtml)
            ?.let { truncateToLimit(it, maxMessageLength - defaultTitle.length - 1) }

        return if (normalizedAnnouncement == null) {
            defaultTitle
        } else {
            "$defaultTitle\n$normalizedAnnouncement"
        }
    }

    private fun buildLine(
        userId: Long?,
        username: String?,
        displayNameSnapshot: String,
        response: AllPingResponse?,
    ): String {
        val status = response?.statusText ?: noResponseText
        return "${MentionFormatter.renderTarget(userId, username, displayNameSnapshot)} - $status"
    }

    private fun buildCounterLine(agreedCount: Int, totalCount: Int): String = "$agreedCounterLabel: $agreedCount/$totalCount"

    private fun validateLineLength(line: String) {
        if (line.length > maxMessageLength) {
            throw AllPingFormattingException("Participant line exceeds Telegram message limit")
        }
    }

    private fun validateChunkLengths(chunks: List<String>): List<String> {
        chunks.forEach { chunk ->
            if (chunk.length > maxMessageLength) {
                throw AllPingFormattingException("All-ping chunk exceeds Telegram message limit")
            }
        }
        return chunks
    }

    private fun truncateToLimit(value: String, limit: Int): String {
        if (value.length <= limit) {
            return value
        }
        if (limit <= 3) {
            return value.take(limit)
        }
        return value.take(limit - 3) + "..."
    }
}

class AllPingFormattingException(message: String) : IllegalArgumentException(message)
