package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Keeps the Java-owned PostgreSQL schema in step with Drizzle's bingo tables. */
public final class BingoRepository {

    private static final String[] SCHEMA_SQL = {
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
        """,
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
        """,
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
        """,
        """
        CREATE INDEX IF NOT EXISTS bingo_milestones_pending_idx
        ON bingo_player_milestones (first_line_announced_at, blackout_announced_at)
        """
    };

    public BingoRepository(HikariDataSource dataSource, Logger logger) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : SCHEMA_SQL) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            logger.error("Failed to ensure bingo schema", e);
        }
    }
}
