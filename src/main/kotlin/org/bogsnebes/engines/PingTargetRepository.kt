package org.bogsnebes.engines

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class PingTargetRepository(databasePath: Path) : Closeable {
    private val connection: Connection = openConnection(databasePath)

    init {
        initialize()
    }

    fun replaceTargets(chatId: Long, targets: List<PingTagTarget>) {
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
                    identity_key,
                    user_id,
                    username,
                    display_name_snapshot
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                targets.forEachIndexed { index, target ->
                    statement.setLong(1, chatId)
                    statement.setInt(2, index)
                    statement.setString(3, target.identityKey)
                    statement.setObject(4, target.userId)
                    statement.setString(5, target.username)
                    statement.setString(6, target.displayNameSnapshot)
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

    fun listTargets(chatId: Long): List<PingTagTarget> {
        connection.prepareStatement(
            """
            SELECT identity_key, user_id, username, display_name_snapshot
            FROM ping_tags
            WHERE chat_id = ?
            ORDER BY position ASC
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { resultSet ->
                val targets = mutableListOf<PingTagTarget>()
                while (resultSet.next()) {
                    targets += PingTagTarget(
                        identityKey = resultSet.getString("identity_key"),
                        userId = resultSet.getLong("user_id").takeIf { !resultSet.wasNull() },
                        username = resultSet.getString("username"),
                        displayNameSnapshot = resultSet.getString("display_name_snapshot"),
                    )
                }
                return targets
            }
        }
    }

    override fun close() {
        connection.close()
    }

    private fun initialize() {
        val columns = tableColumns("ping_tags")
        when {
            columns.isEmpty() -> createPingTagsTable()
            "identity_key" !in columns || "display_name_snapshot" !in columns || "user_id" !in columns -> migrateLegacyPingTags()
        }
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_ping_tags_chat_position
                ON ping_tags(chat_id, position ASC)
                """.trimIndent()
            )
        }
    }

    private fun createPingTagsTable() {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE ping_tags (
                    chat_id INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    identity_key TEXT NOT NULL,
                    user_id INTEGER,
                    username TEXT,
                    display_name_snapshot TEXT NOT NULL,
                    PRIMARY KEY(chat_id, identity_key)
                )
                """.trimIndent()
            )
        }
    }

    private fun migrateLegacyPingTags() {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE ping_tags RENAME TO ping_tags_legacy")
            }
            createPingTagsTable()
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    INSERT INTO ping_tags (
                        chat_id,
                        position,
                        identity_key,
                        user_id,
                        username,
                        display_name_snapshot
                    )
                    SELECT
                        chat_id,
                        position,
                        'n:' || lower(username),
                        NULL,
                        lower(username),
                        '@' || lower(username)
                    FROM ping_tags_legacy
                    """.trimIndent()
                )
                statement.execute("DROP TABLE ping_tags_legacy")
            }
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun openConnection(databasePath: Path): Connection {
        databasePath.parent?.let { Files.createDirectories(it) }
        return DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
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
