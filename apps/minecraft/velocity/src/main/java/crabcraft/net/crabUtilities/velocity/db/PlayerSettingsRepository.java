package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Postgres store for per-player {@code /settings} preferences — the canonical
 * source of truth. The settings value is the same small JSON object the backend
 * Spigot servers exchange over Redis (for example
 * {@code {"phantoms":"off","mentionPings":true,"acceptMessages":true}}), stored
 * verbatim as text so new settings can be added without a migration.
 */
public final class PlayerSettingsRepository {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_settings (
                minecraft_uuid TEXT PRIMARY KEY,
                settings TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """;

    private static final String SELECT_SQL =
            "SELECT settings FROM player_settings WHERE minecraft_uuid = ?";

    private static final String UPSERT_SQL = """
            INSERT INTO player_settings (minecraft_uuid, settings, updated_at)
            VALUES (?, ?, EXTRACT(EPOCH FROM NOW())::INTEGER)
            ON CONFLICT (minecraft_uuid) DO UPDATE SET
                settings = EXCLUDED.settings,
                updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER
            """;

    private final HikariDataSource dataSource;
    private final Logger logger;

    public PlayerSettingsRepository(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
        ensureSchema();
    }

    private void ensureSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
        } catch (SQLException e) {
            logger.error("Failed to ensure player_settings schema", e);
        }
    }

    /** Returns the stored settings JSON for a player, or {@code null} if none. */
    public String load(String uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("settings") : null;
            }
        } catch (SQLException e) {
            logger.error("Failed to read settings for {}", uuid, e);
            return null;
        }
    }

    /** Inserts or updates a player's settings JSON. */
    public void save(String uuid, String settingsJson) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            stmt.setString(1, uuid);
            stmt.setString(2, settingsJson);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save settings for {}", uuid, e);
        }
    }
}
