package crabcraft.net.crabUtilities.velocity.mute;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Postgres persistence for player mutes. The proxy owns this table;
 * backends read the live state from Redis (see {@link RedisMutePublisher}).
 *
 * <p>Expiry is stored as epoch millis. {@code 0} means a permanent mute;
 * any other value is the wall-clock time the mute lapses.
 */
public final class MuteStore {

    /** A persisted mute. {@code expiry == 0} means permanent. */
    public record Mute(UUID uuid, long expiry, String reason, String mutedBy, long createdAt) {
        public boolean permanent() {
            return expiry == 0L;
        }
    }

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_mutes (
                minecraft_uuid TEXT PRIMARY KEY,
                expiry BIGINT NOT NULL,
                reason TEXT,
                muted_by TEXT,
                created_at BIGINT NOT NULL
            )
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO player_mutes (minecraft_uuid, expiry, reason, muted_by, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (minecraft_uuid) DO UPDATE SET
                expiry = EXCLUDED.expiry,
                reason = EXCLUDED.reason,
                muted_by = EXCLUDED.muted_by,
                created_at = EXCLUDED.created_at
            """;

    private static final String DELETE_SQL =
            "DELETE FROM player_mutes WHERE minecraft_uuid = ?";

    private static final String SELECT_SQL =
            "SELECT minecraft_uuid, expiry, reason, muted_by, created_at FROM player_mutes WHERE minecraft_uuid = ?";

    private static final String SELECT_ACTIVE_SQL =
            "SELECT minecraft_uuid, expiry, reason, muted_by, created_at FROM player_mutes WHERE expiry = 0 OR expiry > ?";

    private static final String LOOKUP_UUID_SQL =
            "SELECT minecraft_uuid FROM players WHERE LOWER(minecraft_username) = LOWER(?) LIMIT 1";

    private final HikariDataSource dataSource;
    private final Logger logger;

    public MuteStore(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    public void init() {
        try (Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
        } catch (SQLException e) {
            logger.error("Failed to ensure player_mutes schema", e);
        }
    }

    public void setMute(UUID uuid, long expiry, String reason, String mutedBy) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, expiry);
            stmt.setString(3, reason);
            stmt.setString(4, mutedBy);
            stmt.setLong(5, System.currentTimeMillis());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set mute for {}", uuid, e);
        }
    }

    public void removeMute(UUID uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_SQL)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove mute for {}", uuid, e);
        }
    }

    /** @return the stored mute, or {@code null} if the player isn't muted. */
    public Mute getMute(UUID uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return mapRow(rs);
            }
        } catch (SQLException e) {
            logger.error("Failed to load mute for {}", uuid, e);
            return null;
        }
    }

    /** @return all mutes that are permanent or not yet expired. */
    public List<Mute> getActiveMutes() {
        List<Mute> mutes = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_ACTIVE_SQL)) {
            stmt.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Mute mute = mapRow(rs);
                    if (mute != null) mutes.add(mute);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load active mutes", e);
        }
        return mutes;
    }

    /**
     * Resolves an offline player's UUID from the {@code players} table by
     * username (case-insensitive). Used for muting players who aren't
     * currently online.
     *
     * @return the UUID, or empty if the username isn't known
     */
    public Optional<UUID> lookupUuidByUsername(String username) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(LOOKUP_UUID_SQL)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String raw = rs.getString("minecraft_uuid");
                if (raw == null) return Optional.empty();
                try {
                    return Optional.of(UUID.fromString(raw));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to look up uuid for username {}", username, e);
            return Optional.empty();
        }
    }

    private Mute mapRow(ResultSet rs) throws SQLException {
        String raw = rs.getString("minecraft_uuid");
        UUID uuid;
        try {
            uuid = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            logger.warn("Skipping mute row with malformed uuid {}", raw);
            return null;
        }
        return new Mute(
                uuid,
                rs.getLong("expiry"),
                rs.getString("reason"),
                rs.getString("muted_by"),
                rs.getLong("created_at"));
    }
}
