package org.bogsnebes.engines

enum class AllPingSessionStatus {
    ACTIVE,
    CLOSED,
}

enum class AllPingResponse(
    val callbackCode: String,
    val buttonText: String,
    val statusText: String,
) {
    YES(
        callbackCode = "yes",
        buttonText = "Да",
        statusText = "пойдет",
    ),
    NO(
        callbackCode = "no",
        buttonText = "Нет",
        statusText = "не идет",
    ),
    THINK(
        callbackCode = "think",
        buttonText = "Думаю",
        statusText = "думает",
    ),
    ;

    companion object {
        fun fromCallbackCode(value: String): AllPingResponse? = entries.firstOrNull { it.callbackCode == value }
    }
}

data class AllPingSession(
    val id: Long,
    val chatId: Long,
    val messageThreadId: Long?,
    val announcement: String?,
    val status: AllPingSessionStatus,
    val createdAt: java.time.Instant,
    val closedAt: java.time.Instant?,
    val messages: List<AllPingSessionMessage>,
    val participants: List<AllPingParticipant>,
)

data class AllPingSessionMessage(
    val chunkIndex: Int,
    val messageId: Long,
)

data class AllPingParticipant(
    val username: String,
    val position: Int,
    val chunkIndex: Int,
    val response: AllPingResponse?,
)

object AllPingCallbackData {
    private const val prefix = "all"

    fun encode(sessionId: Long, response: AllPingResponse): String = "$prefix:$sessionId:${response.callbackCode}"

    fun decode(value: String): DecodedAllPingCallback? {
        val parts = value.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != prefix) {
            return null
        }

        val sessionId = parts[1].toLongOrNull() ?: return null
        val response = AllPingResponse.fromCallbackCode(parts[2]) ?: return null
        return DecodedAllPingCallback(sessionId, response)
    }
}

data class DecodedAllPingCallback(
    val sessionId: Long,
    val response: AllPingResponse,
)
