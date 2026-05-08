package crabcraft.net.crabUtilities.velocity.db;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Tracks all-time login streaks per Minecraft account.
 *
 * <p>Streaks measure how many consecutive "play windows" a player has
 * logged in for. A play window is the gap between logins:
 * <ul>
 *   <li>Gap &lt; {@code MIN_INCREMENT_HOURS} (12h): same window, the
 *       streak is unchanged but {@code last_login_at} is bumped.</li>
 *   <li>Gap &le; {@code bufferHours} (default 36h): consecutive
 *       window — streak increments by one.</li>
 *   <li>Gap &gt; {@code bufferHours}: streak resets to 1.</li>
 * </ul>
 *
 * <p>The 12h floor prevents spam-rejoin from inflating streaks. The
 * 36h ceiling lets a player drift their schedule by half a day
 * without losing the run.
 */
public final class LoginStreakService {

    public static final long MIN_INCREMENT_HOURS = 12L;
    public static final long DEFAULT_BUFFER_HOURS = 36L;

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_login_streaks (
                minecraft_uuid TEXT PRIMARY KEY,
                current_streak INTEGER NOT NULL DEFAULT 0,
                longest_streak INTEGER NOT NULL DEFAULT 0,
                last_login_at INTEGER NOT NULL,
                streak_started_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """;

    private static final String CREATE_CURRENT_IDX_SQL =
            "CREATE INDEX IF NOT EXISTS pls_current_streak_idx ON player_login_streaks (current_streak)";
    private static final String CREATE_LONGEST_IDX_SQL =
            "CREATE INDEX IF NOT EXISTS pls_longest_streak_idx ON player_login_streaks (longest_streak)";

    private static final String SELECT_SQL =
            "SELECT current_streak, longest_streak, last_login_at, streak_started_at " +
            "FROM player_login_streaks WHERE minecraft_uuid = ?";

    private static final String UPSERT_SQL = """
            INSERT INTO player_login_streaks
                (minecraft_uuid, current_streak, longest_streak,
                 last_login_at, streak_started_at, updated_at)
            VALUES (?, ?, ?, ?, ?, EXTRACT(EPOCH FROM NOW())::INTEGER)
            ON CONFLICT (minecraft_uuid) DO UPDATE SET
                current_streak = EXCLUDED.current_streak,
                longest_streak = EXCLUDED.longest_streak,
                last_login_at = EXCLUDED.last_login_at,
                streak_started_at = EXCLUDED.streak_started_at,
                updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER
            """;

    private final HikariDataSource dataSource;
    private final Logger logger;
    private volatile long bufferHours;

    public LoginStreakService(HikariDataSource dataSource, Logger logger, long bufferHours) {
        this.dataSource = dataSource;
        this.logger = logger;
        this.bufferHours = clampBuffer(bufferHours);
        ensureSchema();
    }

    public long getBufferHours() {
        return bufferHours;
    }

    public void setBufferHours(long bufferHours) {
        this.bufferHours = clampBuffer(bufferHours);
    }

    private static long clampBuffer(long bufferHours) {
        // Buffer must exceed the same-session floor, otherwise streaks
        // can never increment.
        return Math.max(MIN_INCREMENT_HOURS + 1L, bufferHours);
    }

    private void ensureSchema() {
        try (Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            stmt.execute(CREATE_CURRENT_IDX_SQL);
            stmt.execute(CREATE_LONGEST_IDX_SQL);
        } catch (SQLException e) {
            logger.error("Failed to ensure player_login_streaks schema", e);
        }
    }

    /**
     * Computes and persists the new streak for a player who just logged
     * in. Returns the resulting snapshot, or {@code null} on DB error
     * (so callers can skip Redis fan-out cleanly).
     */
    public StreakSnapshot recordLogin(String uuid) {
        long now = System.currentTimeMillis() / 1000L;
        long bufferSeconds = bufferHours * 3600L;
        long minIncrementSeconds = MIN_INCREMENT_HOURS * 3600L;

        try (Connection conn = dataSource.getConnection()) {
            int currentStreak;
            int longestStreak;
            long lastLoginAt;
            long streakStartedAt;
            boolean hadRow;

            try (PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
                stmt.setString(1, uuid);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        hadRow = true;
                        currentStreak = rs.getInt("current_streak");
                        longestStreak = rs.getInt("longest_streak");
                        lastLoginAt = rs.getLong("last_login_at");
                        streakStartedAt = rs.getLong("streak_started_at");
                    } else {
                        hadRow = false;
                        currentStreak = 0;
                        longestStreak = 0;
                        lastLoginAt = 0L;
                        streakStartedAt = now;
                    }
                }
            }

            long gap = hadRow ? (now - lastLoginAt) : Long.MAX_VALUE;
            int newStreak;
            long newStartedAt;
            if (!hadRow || currentStreak == 0) {
                newStreak = 1;
                newStartedAt = now;
            } else if (gap < minIncrementSeconds) {
                // Same play window — keep the streak as-is.
                newStreak = currentStreak;
                newStartedAt = streakStartedAt;
            } else if (gap <= bufferSeconds) {
                newStreak = currentStreak + 1;
                newStartedAt = streakStartedAt;
            } else {
                // Gap exceeded buffer — start over.
                newStreak = 1;
                newStartedAt = now;
            }
            int newLongest = Math.max(longestStreak, newStreak);

            try (PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
                stmt.setString(1, uuid);
                stmt.setInt(2, newStreak);
                stmt.setInt(3, newLongest);
                stmt.setLong(4, now);
                stmt.setLong(5, newStartedAt);
                stmt.executeUpdate();
            }

            return new StreakSnapshot(newStreak, newLongest, now, newStartedAt);
        } catch (SQLException e) {
            logger.error("Failed to update login streak for {}", uuid, e);
            return null;
        }
    }

    public StreakSnapshot get(String uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return new StreakSnapshot(
                        rs.getInt("current_streak"),
                        rs.getInt("longest_streak"),
                        rs.getLong("last_login_at"),
                        rs.getLong("streak_started_at"));
            }
        } catch (SQLException e) {
            logger.error("Failed to read login streak for {}", uuid, e);
            return null;
        }
    }

    public JsonObject getPlayerStreakJson(String uuid) {
        StreakSnapshot snap = get(uuid);
        if (snap == null) return null;

        long now = System.currentTimeMillis() / 1000L;
        long expiresAt = snap.lastLoginAt + bufferHours * 3600L;
        boolean active = now <= expiresAt;

        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", uuid);
        obj.addProperty("current_streak", active ? snap.currentStreak : 0);
        obj.addProperty("pending_streak", snap.currentStreak);
        obj.addProperty("longest_streak", snap.longestStreak);
        obj.addProperty("last_login_at", snap.lastLoginAt);
        obj.addProperty("streak_started_at", snap.streakStartedAt);
        obj.addProperty("expires_at", expiresAt);
        obj.addProperty("active", active);
        obj.addProperty("buffer_hours", bufferHours);
        return obj;
    }

    public JsonObject getLeaderboard(int limit, int offset, boolean longest) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        String column = longest ? "longest_streak" : "current_streak";

        String listSql = "SELECT s.minecraft_uuid, s.current_streak, s.longest_streak, " +
                "s.last_login_at, s.streak_started_at, p.minecraft_username " +
                "FROM player_login_streaks s " +
                "LEFT JOIN players p ON p.minecraft_uuid = s.minecraft_uuid " +
                "WHERE s." + column + " > 0 " +
                "ORDER BY s." + column + " DESC, s.last_login_at DESC " +
                "LIMIT ? OFFSET ?";
        String countSql = "SELECT COUNT(*) FROM player_login_streaks WHERE " + column + " > 0";

        long bufferSeconds = bufferHours * 3600L;
        long now = System.currentTimeMillis() / 1000L;

        JsonArray entries = new JsonArray();
        int total = 0;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(countSql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) total = rs.getInt(1);
            }

            try (PreparedStatement stmt = conn.prepareStatement(listSql)) {
                stmt.setInt(1, safeLimit);
                stmt.setInt(2, safeOffset);
                try (ResultSet rs = stmt.executeQuery()) {
                    int rank = safeOffset;
                    while (rs.next()) {
                        rank++;
                        int currentStreak = rs.getInt("current_streak");
                        long lastLogin = rs.getLong("last_login_at");
                        boolean active = (now - lastLogin) <= bufferSeconds;
                        JsonObject entry = new JsonObject();
                        entry.addProperty("rank", rank);
                        entry.addProperty("uuid", rs.getString("minecraft_uuid"));
                        entry.addProperty("username", rs.getString("minecraft_username"));
                        entry.addProperty("current_streak", active ? currentStreak : 0);
                        entry.addProperty("pending_streak", currentStreak);
                        entry.addProperty("longest_streak", rs.getInt("longest_streak"));
                        entry.addProperty("last_login_at", lastLogin);
                        entry.addProperty("streak_started_at", rs.getLong("streak_started_at"));
                        entry.addProperty("active", active);
                        entries.add(entry);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to read login streak leaderboard", e);
        }

        JsonObject response = new JsonObject();
        response.addProperty("metric", longest ? "longest" : "current");
        response.addProperty("buffer_hours", bufferHours);
        response.add("leaderboard", entries);
        response.addProperty("total", total);
        response.addProperty("offset", safeOffset);
        response.addProperty("limit", safeLimit);
        return response;
    }

    public static final class StreakSnapshot {
        public final int currentStreak;
        public final int longestStreak;
        public final long lastLoginAt;
        public final long streakStartedAt;

        public StreakSnapshot(int currentStreak, int longestStreak,
                              long lastLoginAt, long streakStartedAt) {
            this.currentStreak = currentStreak;
            this.longestStreak = longestStreak;
            this.lastLoginAt = lastLoginAt;
            this.streakStartedAt = streakStartedAt;
        }
    }
}
