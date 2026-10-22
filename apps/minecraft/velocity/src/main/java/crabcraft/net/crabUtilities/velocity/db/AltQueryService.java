package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class AltQueryService {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_alts (
                id SERIAL PRIMARY KEY,
                discord_id TEXT NOT NULL,
                minecraft_uuid TEXT NOT NULL,
                minecraft_username TEXT NOT NULL,
                nickname TEXT,
                nickname_raw TEXT,
                created_at INTEGER NOT NULL,
                CONSTRAINT player_alts_minecraft_uuid_unique UNIQUE (minecraft_uuid),
                CONSTRAINT player_alts_discord_id_players_discord_id_fk
                    FOREIGN KEY (discord_id) REFERENCES players(discord_id)
            )
            """;

    private static final String ADD_NICKNAME_COLUMNS_SQL = """
            ALTER TABLE player_alts
                ADD COLUMN IF NOT EXISTS nickname TEXT,
                ADD COLUMN IF NOT EXISTS nickname_raw TEXT
            """;

    private final HikariDataSource dataSource;
    private final Logger logger;

    public AltQueryService(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
        ensureSchema();
    }

    private void ensureSchema() {
        // Keep the table, constraints and index synchronised with packages/db/src/schema.ts.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE_SQL);
            statement.execute(ADD_NICKNAME_COLUMNS_SQL);
            statement.execute("CREATE INDEX IF NOT EXISTS palt_discord_idx ON player_alts (discord_id)");
        } catch (SQLException e) {
            logger.error("Failed to ensure player_alts schema", e);
        }
    }

    /**
     * Check if a Minecraft UUID is registered as an alt account.
     * Called once per proxy join — zero overhead when nobody is joining.
     */
    public boolean isAlt(String minecraftUuid) {
        String sql = "SELECT 1 FROM player_alts WHERE minecraft_uuid = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, minecraftUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Failed to check alt status for {}", minecraftUuid, e);
            return false;
        }
    }
}
