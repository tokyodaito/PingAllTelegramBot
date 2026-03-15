package org.bogsnebes.engines

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

data class KnownMember(
    val chatId: Long,
    val userId: Long,
    val firstName: String,
    val lastName: String?,
    val username: String?,
    val displayNameSnapshot: String,
    val isBot: Boolean,
    val status: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val lastSeenSource: String,
)

class MemberRepository(databasePath: Path) : Closeable {
    private val connection: Connection = openConnection(databasePath)

    init {
        initialize()
    }

    fun upsertSeenMember(
        chatId: Long,
        user: TelegramUser,
        status: String,
        seenAt: Instant,
        source: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO known_members (
                chat_id,
                user_id,
                first_name,
                last_name,
                username,
                display_name_snapshot,
                is_bot,
                status,
                first_seen_at,
                last_seen_at,
                last_seen_source
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(chat_id, user_id) DO UPDATE SET
                first_name = excluded.first_name,
                last_name = excluded.last_name,
                username = excluded.username,
                display_name_snapshot = excluded.display_name_snapshot,
                is_bot = excluded.is_bot,
                status = excluded.status,
                last_seen_at = excluded.last_seen_at,
                last_seen_source = excluded.last_seen_source
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.setLong(2, user.id)
            statement.setString(3, user.firstName)
            statement.setString(4, user.lastName)
            statement.setString(5, user.username)
            statement.setString(6, user.toDisplayName())
            statement.setInt(7, if (user.isBot) 1 else 0)
            statement.setString(8, status)
            statement.setLong(9, seenAt.toEpochMilli())
            statement.setLong(10, seenAt.toEpochMilli())
            statement.setString(11, source)
            statement.executeUpdate()
        }
    }

    fun listMentionableMembers(chatId: Long, activeSince: Instant): List<KnownMember> {
        connection.prepareStatement(
            """
            SELECT
                chat_id,
                user_id,
                first_name,
                last_name,
                username,
                display_name_snapshot,
                is_bot,
                status,
                first_seen_at,
                last_seen_at,
                last_seen_source
            FROM known_members
            WHERE chat_id = ?
              AND is_bot = 0
              AND status NOT IN ('left', 'kicked')
              AND last_seen_at >= ?
            ORDER BY lower(display_name_snapshot), user_id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.setLong(2, activeSince.toEpochMilli())
            statement.executeQuery().use { resultSet ->
                val members = mutableListOf<KnownMember>()
                while (resultSet.next()) {
                    members += KnownMember(
                        chatId = resultSet.getLong("chat_id"),
                        userId = resultSet.getLong("user_id"),
                        firstName = resultSet.getString("first_name"),
                        lastName = resultSet.getString("last_name"),
                        username = resultSet.getString("username"),
                        displayNameSnapshot = resultSet.getString("display_name_snapshot"),
                        isBot = resultSet.getInt("is_bot") == 1,
                        status = resultSet.getString("status"),
                        firstSeenAt = Instant.ofEpochMilli(resultSet.getLong("first_seen_at")),
                        lastSeenAt = Instant.ofEpochMilli(resultSet.getLong("last_seen_at")),
                        lastSeenSource = resultSet.getString("last_seen_source"),
                    )
                }
                return members
            }
        }
    }

    fun replacePingTags(chatId: Long, usernames: List<String>) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """
                DELETE FROM ping_tags
                WHERE chat_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO ping_tags (
                    chat_id,
                    position,
                    username
                ) VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                usernames.forEachIndexed { index, username ->
                    statement.setLong(1, chatId)
                    statement.setInt(2, index)
                    statement.setString(3, username)
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

    fun listPingTags(chatId: Long): List<String> {
        connection.prepareStatement(
            """
            SELECT username
            FROM ping_tags
            WHERE chat_id = ?
            ORDER BY position ASC
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { resultSet ->
                val usernames = mutableListOf<String>()
                while (resultSet.next()) {
                    usernames += resultSet.getString("username")
                }
                return usernames
            }
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
                CREATE TABLE IF NOT EXISTS known_members (
                    chat_id INTEGER NOT NULL,
                    user_id INTEGER NOT NULL,
                    first_name TEXT NOT NULL,
                    last_name TEXT,
                    username TEXT,
                    display_name_snapshot TEXT NOT NULL,
                    is_bot INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    first_seen_at INTEGER NOT NULL,
                    last_seen_at INTEGER NOT NULL,
                    last_seen_source TEXT NOT NULL,
                    PRIMARY KEY(chat_id, user_id)
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_known_members_chat_seen
                ON known_members(chat_id, last_seen_at DESC)
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS ping_tags (
                    chat_id INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    username TEXT NOT NULL,
                    PRIMARY KEY(chat_id, username)
                )
                """.trimIndent()
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_ping_tags_chat_position
                ON ping_tags(chat_id, position ASC)
                """.trimIndent()
            )
        }
    }

    private fun openConnection(databasePath: Path): Connection {
        databasePath.parent?.let { Files.createDirectories(it) }
        return DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
    }
}
