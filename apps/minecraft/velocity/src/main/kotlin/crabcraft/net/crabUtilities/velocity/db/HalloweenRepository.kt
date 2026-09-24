package crabcraft.net.crabUtilities.velocity.db

import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.sql.Statement
import org.slf4j.Logger

/** Keeps the Halloween schema in step with Drizzle. */
class HalloweenRepository {
    constructor(dataSource: HikariDataSource, logger: Logger) {
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    for (sql in SCHEMA_SQL) statement.execute(sql)
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to ensure Halloween schema", e)
        }
    }

    companion object {
        private val SCHEMA_SQL: Array<String> =
            arrayOf(
                ("""
                CREATE TABLE IF NOT EXISTS halloween_events (
                    id TEXT PRIMARY KEY,
                    starts_at INTEGER NOT NULL,
                    ends_at INTEGER NOT NULL,
                    guild_id TEXT NOT NULL,
                    role_id TEXT NOT NULL
                )
                """
                    .trimIndent() + "\n"),
                ("""
                CREATE TABLE IF NOT EXISTS halloween_player_progress (
                    event_id TEXT NOT NULL REFERENCES halloween_events(id) ON DELETE CASCADE,
                    minecraft_uuid TEXT NOT NULL,
                    hunt_mask INTEGER NOT NULL DEFAULT 0,
                    trick_or_treat BOOLEAN NOT NULL DEFAULT FALSE,
                    back_from_the_dead BOOLEAN NOT NULL DEFAULT FALSE,
                    last_stream_id TEXT NOT NULL DEFAULT '0-0',
                    completed_at INTEGER,
                    role_awarded_at INTEGER,
                    PRIMARY KEY (event_id, minecraft_uuid)
                )
                """
                    .trimIndent() + "\n"),
            )
    }
}
