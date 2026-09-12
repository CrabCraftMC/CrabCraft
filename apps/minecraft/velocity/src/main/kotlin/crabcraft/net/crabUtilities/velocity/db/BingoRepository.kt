package crabcraft.net.crabUtilities.velocity.db

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.SQLException

/** Keeps the Kotlin-owned PostgreSQL schema in step with Drizzle's bingo tables. */
class BingoRepository(dataSource: HikariDataSource, logger: Logger) {
    init {
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    for (sql in SCHEMA_SQL) statement.execute(sql)
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to ensure bingo schema", e)
        }
    }

    companion object {
        private val SCHEMA_SQL = arrayOf(
        """
        CREATE TABLE IF NOT EXISTS bingo_cards (
            id SERIAL PRIMARY KEY,
            number INTEGER NOT NULL,
            starts_at INTEGER NOT NULL,
            ends_at INTEGER NOT NULL,
            tasks JSONB NOT NULL,
            announcement_guild_id TEXT,
            announcement_channel_id TEXT,
            announcement_message_id TEXT,
            posted_at INTEGER,
            created_at INTEGER NOT NULL
        )
        """.trimIndent() + "\n",
        "CREATE UNIQUE INDEX IF NOT EXISTS bingo_cards_number_unique ON bingo_cards (number)",
        "CREATE INDEX IF NOT EXISTS bingo_cards_active_idx ON bingo_cards (starts_at, ends_at)",
        """
        CREATE TABLE IF NOT EXISTS bingo_player_progress (
            card_id INTEGER NOT NULL REFERENCES bingo_cards(id) ON DELETE CASCADE,
            minecraft_uuid TEXT NOT NULL,
            source_minecraft_uuid TEXT NOT NULL,
            task_id TEXT NOT NULL,
            completed_at INTEGER NOT NULL,
            source_backend TEXT,
            PRIMARY KEY (card_id, minecraft_uuid, task_id)
        )
        """.trimIndent() + "\n",
        "CREATE INDEX IF NOT EXISTS bingo_progress_player_idx ON bingo_player_progress (minecraft_uuid, card_id)",
        """
        CREATE TABLE IF NOT EXISTS bingo_player_milestones (
            card_id INTEGER NOT NULL REFERENCES bingo_cards(id) ON DELETE CASCADE,
            minecraft_uuid TEXT NOT NULL,
            first_line_completed_at INTEGER,
            first_line_announced_at INTEGER,
            first_line_role_awarded_at INTEGER,
            blackout_completed_at INTEGER,
            blackout_announced_at INTEGER,
            blackout_role_awarded_at INTEGER,
            PRIMARY KEY (card_id, minecraft_uuid)
        )
        """.trimIndent() + "\n",
        """
        CREATE INDEX IF NOT EXISTS bingo_milestones_pending_idx
        ON bingo_player_milestones (first_line_announced_at, blackout_announced_at)
        """.trimIndent() + "\n"
        )
    }
}
