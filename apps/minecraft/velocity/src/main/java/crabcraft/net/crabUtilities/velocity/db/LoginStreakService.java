package crabcraft.net.crabUtilities.velocity.db;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Tracks all-time login streaks per Minecraft account.
 *
 * <p>A streak counts the days a player has been online long enough to
 * qualify, where a "day" is a fixed 24-hour window that rolls over at
 * {@code resetHourUtc}:00 UTC (06:00 by default). The proxy accumulates
 * online seconds across sessions and records the streak only once the
 * configured daily requirement is reached:
 * <ul>
 *   <li>Same UTC streak-day as the last qualified day: no change.</li>
 *   <li>The next day, or after a single missed day: the streak
 *       increments by one. Missing one day is forgiven — the streak
 *       holds and continues, but the missed day itself earns no point.</li>
 *   <li>Two or more missed days in a row: the streak resets to 1.</li>
 * </ul>
 *
 * <p>So qualifying Mon, Tue, Wed gives a streak of 3; qualifying Mon and
 * Wed (missing Tue) gives 2; qualifying Mon then Thu (missing both Tue
 * and Wed) resets to 1.
 *
 * <p>Alt accounts (rows in {@code player_alts}) never build a streak:
 * their current and longest streaks are capped at 1, and they are
 * excluded from the streak leaderboard entirely.
 */
public final class LoginStreakService {

    public static final int DEFAULT_RESET_HOUR_UTC = 6;
    public static final int DEFAULT_REQUIRED_PLAY_MINUTES = 10;
    private static final long DAY_SECONDS = 86_400L;
    private static final int SECONDS_PER_MINUTE = 60;

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

    private static final String CREATE_PROGRESS_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_login_streak_progress (
                minecraft_uuid TEXT NOT NULL,
                streak_day BIGINT NOT NULL,
                accumulated_seconds INTEGER NOT NULL DEFAULT 0,
                qualified_at INTEGER,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (minecraft_uuid, streak_day)
            )
            """;

    private static final String SELECT_SQL =
            "SELECT current_streak, longest_streak, last_login_at, streak_started_at " +
            "FROM player_login_streaks WHERE minecraft_uuid = ?";

    private static final String SELECT_PROGRESS_SQL =
            "SELECT accumulated_seconds, qualified_at " +
            "FROM player_login_streak_progress WHERE minecraft_uuid = ? AND streak_day = ?";

    private static final String SELECT_PROGRESS_FOR_UPDATE_SQL =
            SELECT_PROGRESS_SQL + " FOR UPDATE";

    private static final String INSERT_PROGRESS_ROW_SQL = """
            INSERT INTO player_login_streak_progress
                (minecraft_uuid, streak_day, accumulated_seconds, updated_at)
            VALUES (?, ?, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
            ON CONFLICT (minecraft_uuid, streak_day) DO NOTHING
            """;

    private static final String UPDATE_PROGRESS_SQL = """
            UPDATE player_login_streak_progress SET
                accumulated_seconds = ?,
                qualified_at = ?,
                updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER
            WHERE minecraft_uuid = ? AND streak_day = ?
            """;

    private static final String IS_ALT_SQL =
            "SELECT 1 FROM player_alts WHERE minecraft_uuid = ? LIMIT 1";

    private static final String CAP_ALT_STREAK_SQL = """
            UPDATE player_login_streaks SET
                current_streak = LEAST(current_streak, 1),
                longest_streak = LEAST(longest_streak, 1),
                streak_started_at = CASE
                    WHEN current_streak > 1 THEN last_login_at
                    ELSE streak_started_at
                END,
                updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER
            WHERE minecraft_uuid = ?
              AND (current_streak > 1 OR longest_streak > 1)
            """;

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
    private final int resetHourUtc;
    private final int requiredPlaySeconds;

    public LoginStreakService(HikariDataSource dataSource, Logger logger,
                              int resetHourUtc, int requiredPlaySeconds) {
        this.dataSource = dataSource;
        this.logger = logger;
        this.resetHourUtc = clampResetHour(resetHourUtc);
        this.requiredPlaySeconds = Math.max(1, requiredPlaySeconds);
        ensureSchema();
    }

    public int getResetHourUtc() {
        return resetHourUtc;
    }

    public int getRequiredPlaySeconds() {
        return requiredPlaySeconds;
    }

    public static int minutesToSeconds(int minutes) {
        return Math.max(1, minutes) * SECONDS_PER_MINUTE;
    }

    private static int clampResetHour(int resetHourUtc) {
        if (resetHourUtc < 0) return 0;
        if (resetHourUtc > 23) return 23;
        return resetHourUtc;
    }

    /** The streak-day number a Unix timestamp falls in, given the reset hour. */
    private static long dayNumber(long epochSeconds, int resetHourUtc) {
        return Math.floorDiv(epochSeconds - resetHourUtc * 3600L, DAY_SECONDS);
    }

    private static long startOfDay(long streakDay, int resetHourUtc) {
        return streakDay * DAY_SECONDS + resetHourUtc * 3600L;
    }

    public long secondsUntilNextStreakDay(long epochSeconds) {
        int rh = resetHourUtc;
        long today = dayNumber(epochSeconds, rh);
        return Math.max(0L, startOfDay(today + 1, rh) - epochSeconds);
    }

    /**
     * Unix second at which a streak lapses: the start of the third
     * streak-day after the last qualified day. Qualifying the next day (gap 1) or
     * the day after (gap 2, one forgiven miss) keeps the streak alive;
     * once this instant passes, the next login resets the streak to 1.
     */
    public static long expiryOf(long lastLoginAt, int resetHourUtc) {
        long lastDay = dayNumber(lastLoginAt, resetHourUtc);
        return (lastDay + 3) * DAY_SECONDS + resetHourUtc * 3600L;
    }

    private void ensureSchema() {
        try (Connection conn = dataSource.getConnection();
            java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            stmt.execute(CREATE_CURRENT_IDX_SQL);
            stmt.execute(CREATE_LONGEST_IDX_SQL);
            stmt.execute(CREATE_PROGRESS_TABLE_SQL);
        } catch (SQLException e) {
            logger.error("Failed to ensure player_login_streaks schema", e);
        }
    }

    private StreakSnapshot recordLoginAt(Connection conn, String uuid, long loginAt) throws SQLException {
        int rh = resetHourUtc;
        long today = dayNumber(loginAt, rh);

        int currentStreak;
        int longestStreak;
        long streakStartedAt;
        boolean hadRow;
        long lastDay;
        long storedLastLoginAt;

        try (PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    hadRow = true;
                    currentStreak = rs.getInt("current_streak");
                    longestStreak = rs.getInt("longest_streak");
                    storedLastLoginAt = rs.getLong("last_login_at");
                    lastDay = dayNumber(storedLastLoginAt, rh);
                    streakStartedAt = rs.getLong("streak_started_at");
                } else {
                    hadRow = false;
                    currentStreak = 0;
                    longestStreak = 0;
                    storedLastLoginAt = 0L;
                    lastDay = today; // unused on the first-login path; set for definite assignment
                    streakStartedAt = loginAt;
                }
            }
        }

        int newStreak;
        long newStartedAt;
        long newLastLoginAt = loginAt;
        if (!hadRow || currentStreak == 0) {
            newStreak = 1;
            newStartedAt = loginAt;
        } else {
            long gap = today - lastDay;
            if (gap < 0) {
                // A delayed write for an older streak day should never
                // rewind the authoritative latest qualified day.
                return new StreakSnapshot(currentStreak, longestStreak, storedLastLoginAt, streakStartedAt);
            } else if (gap == 0) {
                // Already qualified today — streak unchanged.
                newStreak = currentStreak;
                newStartedAt = streakStartedAt;
                newLastLoginAt = Math.max(storedLastLoginAt, loginAt);
            } else if (gap <= 2) {
                // Next day, or a single forgiven missed day — increment.
                newStreak = currentStreak + 1;
                newStartedAt = streakStartedAt;
            } else {
                // Two or more days missed — start over.
                newStreak = 1;
                newStartedAt = loginAt;
            }
        }
        int newLongest = Math.max(longestStreak, newStreak);

        // Alt accounts only ever hold a one-day streak at most.
        if (isAltAccount(conn, uuid)) {
            if (newStreak > 1) {
                newStreak = 1;
                newStartedAt = loginAt;
            }
            newLongest = Math.min(newLongest, 1);
        }

        try (PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
            stmt.setString(1, uuid);
            stmt.setInt(2, newStreak);
            stmt.setInt(3, newLongest);
            stmt.setLong(4, newLastLoginAt);
            stmt.setLong(5, newStartedAt);
            stmt.executeUpdate();
        }

        return new StreakSnapshot(newStreak, newLongest, newLastLoginAt, newStartedAt);
    }

    private static boolean isAltAccount(Connection conn, String uuid) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(IS_ALT_SQL)) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Clamps an alt account's stored streak to a single day. Called when
     * the proxy identifies a connecting player as an alt, so streaks
     * accumulated before the account was registered as an alt (or before
     * alt capping existed) are normalized without waiting for the next
     * qualified day.
     */
    public void capAltStreak(String uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CAP_ALT_STREAK_SQL)) {
            stmt.setString(1, uuid);
            int updated = stmt.executeUpdate();
            if (updated > 0) {
                logger.info("Capped login streak for alt account {}", uuid);
            }
        } catch (SQLException e) {
            logger.error("Failed to cap login streak for alt {}", uuid, e);
        }
    }

    public PlaytimeCreditResult recordPlaytime(String uuid, long from, long to) {
        if (to <= from) {
            return new PlaytimeCreditResult(null, getQualificationProgress(uuid, to));
        }

        StreakSnapshot latestSnapshot = null;
        QualificationProgress progress = null;
        try (Connection conn = dataSource.getConnection()) {
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int rh = resetHourUtc;
                long cursor = from;
                while (cursor < to) {
                    long day = dayNumber(cursor, rh);
                    long segmentEnd = Math.min(to, startOfDay(day + 1, rh));
                    long qualifiedAt = creditDay(conn, uuid, day, cursor, segmentEnd);
                    if (qualifiedAt > 0L) {
                        latestSnapshot = recordLoginAt(conn, uuid, qualifiedAt);
                    }
                    cursor = segmentEnd;
                }
                progress = loadQualificationProgress(conn, uuid, to);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(oldAutoCommit);
            }
            return new PlaytimeCreditResult(latestSnapshot, progress);
        } catch (SQLException e) {
            logger.error("Failed to record login streak playtime for {}", uuid, e);
            return null;
        }
    }

    public QualificationProgress getQualificationProgress(String uuid) {
        long now = System.currentTimeMillis() / 1000L;
        return getQualificationProgress(uuid, now);
    }

    public QualificationProgress getQualificationProgress(String uuid, long at) {
        try (Connection conn = dataSource.getConnection()) {
            return loadQualificationProgress(conn, uuid, at);
        } catch (SQLException e) {
            logger.error("Failed to read login streak progress for {}", uuid, e);
            return null;
        }
    }

    private long creditDay(Connection conn, String uuid, long streakDay,
                           long segmentStart, long segmentEnd) throws SQLException {
        long seconds = segmentEnd - segmentStart;
        if (seconds <= 0L) return 0L;

        ensureProgressRow(conn, uuid, streakDay);

        int accumulated;
        long qualifiedAt;
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_PROGRESS_FOR_UPDATE_SQL)) {
            stmt.setString(1, uuid);
            stmt.setLong(2, streakDay);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Missing progress row after insert");
                }
                accumulated = rs.getInt("accumulated_seconds");
                qualifiedAt = nullableLong(rs, "qualified_at");
            }
        }

        if (qualifiedAt > 0L) return 0L;

        Long recordedAt = recordedLoginAtForDay(conn, uuid, streakDay);
        if (recordedAt != null) {
            updateProgress(conn, uuid, streakDay, accumulated, recordedAt);
            return 0L;
        }

        int required = requiredPlaySeconds;
        int newAccumulated = (int) Math.min(Integer.MAX_VALUE, accumulated + seconds);
        long newQualifiedAt = 0L;
        if (accumulated < required && newAccumulated >= required) {
            long crossedAt = segmentStart + (required - accumulated);
            newQualifiedAt = Math.min(crossedAt, segmentEnd - 1L);
        }

        updateProgress(conn, uuid, streakDay, newAccumulated, newQualifiedAt);
        return newQualifiedAt;
    }

    private void ensureProgressRow(Connection conn, String uuid, long streakDay) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_PROGRESS_ROW_SQL)) {
            stmt.setString(1, uuid);
            stmt.setLong(2, streakDay);
            stmt.executeUpdate();
        }
    }

    private void updateProgress(Connection conn, String uuid, long streakDay,
                                int accumulated, long qualifiedAt) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UPDATE_PROGRESS_SQL)) {
            stmt.setInt(1, accumulated);
            if (qualifiedAt > 0L) {
                stmt.setLong(2, qualifiedAt);
            } else {
                stmt.setNull(2, Types.INTEGER);
            }
            stmt.setString(3, uuid);
            stmt.setLong(4, streakDay);
            stmt.executeUpdate();
        }
    }

    private QualificationProgress loadQualificationProgress(Connection conn, String uuid, long at) throws SQLException {
        int rh = resetHourUtc;
        long day = dayNumber(at, rh);
        int accumulated = 0;
        long qualifiedAt = 0L;

        try (PreparedStatement stmt = conn.prepareStatement(SELECT_PROGRESS_SQL)) {
            stmt.setString(1, uuid);
            stmt.setLong(2, day);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    accumulated = rs.getInt("accumulated_seconds");
                    qualifiedAt = nullableLong(rs, "qualified_at");
                }
            }
        }

        Long recordedAt = recordedLoginAtForDay(conn, uuid, day);
        if (recordedAt != null && qualifiedAt == 0L) {
            qualifiedAt = recordedAt;
        }

        return new QualificationProgress(day, accumulated, requiredPlaySeconds, qualifiedAt);
    }

    private Long recordedLoginAtForDay(Connection conn, String uuid, long streakDay) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                long lastLoginAt = rs.getLong("last_login_at");
                return dayNumber(lastLoginAt, resetHourUtc) == streakDay ? lastLoginAt : null;
            }
        }
    }

    private static long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
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
        long expiresAt = expiryOf(snap.lastLoginAt, resetHourUtc);
        boolean active = now < expiresAt;

        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", uuid);
        obj.addProperty("current_streak", active ? snap.currentStreak : 0);
        obj.addProperty("pending_streak", snap.currentStreak);
        obj.addProperty("longest_streak", snap.longestStreak);
        obj.addProperty("last_login_at", snap.lastLoginAt);
        obj.addProperty("streak_started_at", snap.streakStartedAt);
        obj.addProperty("expires_at", expiresAt);
        obj.addProperty("active", active);
        return obj;
    }

    public JsonObject getLeaderboard(int limit, int offset, boolean longest) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        int safeOffset = Math.max(0, offset);
        String column = longest ? "longest_streak" : "current_streak";

        int rh = resetHourUtc;
        long now = System.currentTimeMillis() / 1000L;
        // Streaks reset lazily (on the player's next qualified day), so the
        // stored current_streak of a long-absent player is stale. The current
        // leaderboard must drop lapsed streaks, or absent players keep their
        // rank indefinitely. last_login_at >= startOfDay(today - 2) is exactly
        // the "now < expiryOf(last_login_at)" liveness check in SQL form.
        long activeCutoff = startOfDay(dayNumber(now, rh) - 2, rh);
        String activeFilter = longest ? "" : "AND s.last_login_at >= ? ";

        // Alt accounts are excluded — they only ever hold a one-day streak
        // and should not occupy leaderboard ranks alongside main accounts.
        String notAlt = "AND NOT EXISTS (SELECT 1 FROM player_alts pa WHERE pa.minecraft_uuid = s.minecraft_uuid) ";

        String listSql = "SELECT s.minecraft_uuid, s.current_streak, s.longest_streak, " +
                "s.last_login_at, s.streak_started_at, p.minecraft_username " +
                "FROM player_login_streaks s " +
                "LEFT JOIN players p ON p.minecraft_uuid = s.minecraft_uuid " +
                "WHERE s." + column + " > 0 " + activeFilter + notAlt +
                "ORDER BY s." + column + " DESC, s.last_login_at DESC " +
                "LIMIT ? OFFSET ?";
        String countSql = "SELECT COUNT(*) FROM player_login_streaks s " +
                "WHERE s." + column + " > 0 " + activeFilter + notAlt;

        JsonArray entries = new JsonArray();
        int total = 0;
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
                if (!longest) stmt.setLong(1, activeCutoff);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(listSql)) {
                int param = 0;
                if (!longest) stmt.setLong(++param, activeCutoff);
                stmt.setInt(++param, safeLimit);
                stmt.setInt(++param, safeOffset);
                try (ResultSet rs = stmt.executeQuery()) {
                    int rank = safeOffset;
                    while (rs.next()) {
                        rank++;
                        int currentStreak = rs.getInt("current_streak");
                        long lastLogin = rs.getLong("last_login_at");
                        boolean active = now < expiryOf(lastLogin, rh);
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

    public static final class QualificationProgress {
        public final long streakDay;
        public final int accumulatedSeconds;
        public final int requiredSeconds;
        public final long qualifiedAt;
        public final boolean qualified;

        public QualificationProgress(long streakDay, int accumulatedSeconds,
                                     int requiredSeconds, long qualifiedAt) {
            this.streakDay = streakDay;
            this.accumulatedSeconds = accumulatedSeconds;
            this.requiredSeconds = requiredSeconds;
            this.qualifiedAt = qualifiedAt;
            this.qualified = qualifiedAt > 0L;
        }

        public int remainingSeconds() {
            if (qualified) return 0;
            return Math.max(0, requiredSeconds - accumulatedSeconds);
        }
    }

    public static final class PlaytimeCreditResult {
        public final StreakSnapshot streakSnapshot;
        public final QualificationProgress progress;

        public PlaytimeCreditResult(StreakSnapshot streakSnapshot, QualificationProgress progress) {
            this.streakSnapshot = streakSnapshot;
            this.progress = progress;
        }
    }
}
