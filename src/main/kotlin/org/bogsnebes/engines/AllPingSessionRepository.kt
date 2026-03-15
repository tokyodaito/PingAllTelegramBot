package org.bogsnebes.engines

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class AllPingSessionRepository(databasePath: Path) : Closeable {
    private val connection: Connection = openConnection(databasePath)

    init {
        initialize()
    }

    fun createSession(
        chatId: Long,
        messageThreadId: Long?,
        announcement: String?,
        targets: List<PingTagTarget>,
        createdAt: Instant,
    ): AllPingSession {
        val chunkIndexes = AllPingFormatter.prepareChunkIndexes(targets, announcement)
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false

        try {
            val sessionId = connection.prepareStatement(
                """
                INSERT INTO all_ping_sessions (
                    chat_id,
                    message_thread_id,
                    announcement,
                    status,
                    created_at,
                    closed_at
                ) VALUES (?, ?, ?, ?, ?, NULL)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.setObject(2, messageThreadId)
                statement.setString(3, announcement)
                statement.setString(4, AllPingSessionStatus.ACTIVE.name)
                statement.setLong(5, createdAt.toEpochMilli())
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (!keys.next()) {
                        error("Failed to create all ping session for chat $chatId")
                    }
                    keys.getLong(1)
                }
            }

            connection.prepareStatement(
                """
                INSERT INTO all_ping_session_participants (
                    session_id,
                    identity_key,
                    user_id,
                    username,
                    display_name_snapshot,
                    position,
                    chunk_index,
                    response_code,
                    responded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                """.trimIndent(),
            ).use { statement ->
                targets.forEachIndexed { index, target ->
                    statement.setLong(1, sessionId)
                    statement.setString(2, target.identityKey)
                    statement.setObject(3, target.userId)
                    statement.setString(4, target.username)
                    statement.setString(5, target.displayNameSnapshot)
                    statement.setInt(6, index)
                    statement.setInt(7, chunkIndexes[index])
                    statement.addBatch()
                }
                statement.executeBatch()
            }

            connection.commit()
            return requireNotNull(findSession(sessionId))
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    fun saveMessages(sessionId: Long, sentMessages: List<TelegramSentMessage>) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false

        try {
            connection.prepareStatement(
                """
                DELETE FROM all_ping_session_messages
                WHERE session_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, sessionId)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO all_ping_session_messages (
                    session_id,
                    chunk_index,
                    message_id
                ) VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                sentMessages.forEachIndexed { index, message ->
                    statement.setLong(1, sessionId)
                    statement.setInt(2, index)
                    statement.setLong(3, message.messageId)
                    statement.addBatch()
                }
                statement.executeBatch()
            }

            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    fun findActiveSession(chatId: Long): AllPingSession? {
        connection.prepareStatement(
            """
            SELECT id
            FROM all_ping_sessions
            WHERE chat_id = ?
              AND status = ?
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.setString(2, AllPingSessionStatus.ACTIVE.name)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                return findSession(resultSet.getLong("id"))
            }
        }
    }

    fun findSession(sessionId: Long): AllPingSession? {
        val sessionRow = connection.prepareStatement(
            """
            SELECT
                id,
                chat_id,
                message_thread_id,
                announcement,
                status,
                created_at,
                closed_at
            FROM all_ping_sessions
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }

                SessionRow(
                    id = resultSet.getLong("id"),
                    chatId = resultSet.getLong("chat_id"),
                    messageThreadId = resultSet.getLong("message_thread_id").takeIf { !resultSet.wasNull() },
                    announcement = resultSet.getString("announcement"),
                    status = AllPingSessionStatus.valueOf(resultSet.getString("status")),
                    createdAt = Instant.ofEpochMilli(resultSet.getLong("created_at")),
                    closedAt = resultSet.getLong("closed_at").takeIf { !resultSet.wasNull() }?.let(Instant::ofEpochMilli),
                )
            }
        }

        val messages = connection.prepareStatement(
            """
            SELECT chunk_index, message_id
            FROM all_ping_session_messages
            WHERE session_id = ?
            ORDER BY chunk_index ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            AllPingSessionMessage(
                                chunkIndex = resultSet.getInt("chunk_index"),
                                messageId = resultSet.getLong("message_id"),
                            ),
                        )
                    }
                }
            }
        }

        val participants = connection.prepareStatement(
            """
            SELECT identity_key, user_id, username, display_name_snapshot, position, chunk_index, response_code
            FROM all_ping_session_participants
            WHERE session_id = ?
            ORDER BY position ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            AllPingParticipant(
                                identityKey = resultSet.getString("identity_key"),
                                userId = resultSet.getLong("user_id").takeIf { !resultSet.wasNull() },
                                username = resultSet.getString("username"),
                                displayNameSnapshot = resultSet.getString("display_name_snapshot"),
                                position = resultSet.getInt("position"),
                                chunkIndex = resultSet.getInt("chunk_index"),
                                response = resultSet.getString("response_code")?.let(AllPingResponse::valueOf),
                            ),
                        )
                    }
                }
            }
        }

        return AllPingSession(
            id = sessionRow.id,
            chatId = sessionRow.chatId,
            messageThreadId = sessionRow.messageThreadId,
            announcement = sessionRow.announcement,
            status = sessionRow.status,
            createdAt = sessionRow.createdAt,
            closedAt = sessionRow.closedAt,
            messages = messages,
            participants = participants,
        )
    }

    fun updateResponse(
        sessionId: Long,
        identityKey: String,
        response: AllPingResponse,
        respondedAt: Instant,
    ): Boolean {
        connection.prepareStatement(
            """
            UPDATE all_ping_session_participants
            SET response_code = ?, responded_at = ?
            WHERE session_id = ?
              AND identity_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, response.name)
            statement.setLong(2, respondedAt.toEpochMilli())
            statement.setLong(3, sessionId)
            statement.setString(4, identityKey)
            return statement.executeUpdate() > 0
        }
    }

    fun closeSession(sessionId: Long, closedAt: Instant) {
        connection.prepareStatement(
            """
            UPDATE all_ping_sessions
            SET status = ?,
                closed_at = ?
            WHERE id = ?
              AND status = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, AllPingSessionStatus.CLOSED.name)
            statement.setLong(2, closedAt.toEpochMilli())
            statement.setLong(3, sessionId)
            statement.setString(4, AllPingSessionStatus.ACTIVE.name)
            statement.executeUpdate()
        }
    }

    override fun close() {
        connection.close()
    }

    private fun initialize() {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS all_ping_sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id INTEGER NOT NULL,
                    message_thread_id INTEGER,
                    announcement TEXT,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    closed_at INTEGER
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_all_ping_sessions_chat_status
                ON all_ping_sessions(chat_id, status, created_at DESC)
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS all_ping_session_messages (
                    session_id INTEGER NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    message_id INTEGER NOT NULL,
                    PRIMARY KEY(session_id, chunk_index)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS all_ping_session_participants (
                    session_id INTEGER NOT NULL,
                    identity_key TEXT NOT NULL,
                    user_id INTEGER,
                    username TEXT,
                    display_name_snapshot TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    response_code TEXT,
                    responded_at INTEGER,
                    PRIMARY KEY(session_id, identity_key)
                )
                """.trimIndent(),
            )
        }
        migrateLegacyParticipantsIfNeeded()
    }

    private fun openConnection(databasePath: Path): Connection {
        databasePath.parent?.let { Files.createDirectories(it) }
        return DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
    }

    private data class SessionRow(
        val id: Long,
        val chatId: Long,
        val messageThreadId: Long?,
        val announcement: String?,
        val status: AllPingSessionStatus,
        val createdAt: Instant,
        val closedAt: Instant?,
    )

    private fun migrateLegacyParticipantsIfNeeded() {
        val columns = tableColumns("all_ping_session_participants")
        if ("identity_key" in columns && "display_name_snapshot" in columns && "user_id" in columns) {
            return
        }

        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE all_ping_session_participants RENAME TO all_ping_session_participants_legacy")
                statement.execute(
                    """
                    CREATE TABLE all_ping_session_participants (
                        session_id INTEGER NOT NULL,
                        identity_key TEXT NOT NULL,
                        user_id INTEGER,
                        username TEXT,
                        display_name_snapshot TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        chunk_index INTEGER NOT NULL,
                        response_code TEXT,
                        responded_at INTEGER,
                        PRIMARY KEY(session_id, identity_key)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    INSERT INTO all_ping_session_participants (
                        session_id,
                        identity_key,
                        user_id,
                        username,
                        display_name_snapshot,
                        position,
                        chunk_index,
                        response_code,
                        responded_at
                    )
                    SELECT
                        session_id,
                        'n:' || lower(username),
                        NULL,
                        lower(username),
                        '@' || lower(username),
                        position,
                        chunk_index,
                        response_code,
                        responded_at
                    FROM all_ping_session_participants_legacy
                    """.trimIndent(),
                )
                statement.execute("DROP TABLE all_ping_session_participants_legacy")
            }
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun tableColumns(tableName: String): Set<String> {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
                val columns = linkedSetOf<String>()
                while (resultSet.next()) {
                    columns += resultSet.getString("name")
                }
                return columns
            }
        }
    }
}
