package crabcraft.net.crabUtilities.velocity.db

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/**
 * Tracks qualified all-time login streak days with one forgiven missed day.
 * Daily playtime accumulates across sessions; alt accounts are capped at one.
 */
class LoginStreakService(private val dataSource: HikariDataSource, private val logger: Logger,
    resetHourUtc: Int, requiredPlaySeconds: Int) {
    private val resetHourUtc = clampResetHour(resetHourUtc)
    private val requiredPlaySeconds = Math.max(1, requiredPlaySeconds)
    init { ensureSchema() }

    fun getResetHourUtc(): Int = resetHourUtc
    fun getRequiredPlaySeconds(): Int = requiredPlaySeconds
    fun secondsUntilNextStreakDay(epochSeconds: Long): Long {
        val rh = resetHourUtc
        val today = dayNumber(epochSeconds, rh)
        return Math.max(0L, startOfDay(today + 1, rh) - epochSeconds)
    }

    private fun ensureSchema() {
        try {
            dataSource.getConnection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(CREATE_TABLE_SQL)
                    stmt.execute(CREATE_CURRENT_IDX_SQL)
                    stmt.execute(CREATE_LONGEST_IDX_SQL)
                    stmt.execute(CREATE_PROGRESS_TABLE_SQL)
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to ensure player_login_streaks schema", e)
        }
    }

    private fun recordLoginAt(conn: Connection, uuid: String?, loginAt: Long): StreakSnapshot {
        var rh = resetHourUtc
        var today = dayNumber(loginAt, rh)

        var currentStreak: Int
        var longestStreak: Int
        var streakStartedAt: Long
        var hadRow: Boolean
        var lastDay: Long
        var storedLastLoginAt: Long

        conn.prepareStatement(SELECT_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    hadRow = true
                    currentStreak = rs.getInt("current_streak")
                    longestStreak = rs.getInt("longest_streak")
                    storedLastLoginAt = rs.getLong("last_login_at")
                    lastDay = dayNumber(storedLastLoginAt, rh)
                    streakStartedAt = rs.getLong("streak_started_at")
                } else {
                    hadRow = false
                    currentStreak = 0
                    longestStreak = 0
                    storedLastLoginAt = 0L
                    lastDay = today // unused on the first-login path; set for definite assignment
                    streakStartedAt = loginAt
                }
            }
        }

        var newStreak: Int
        var newStartedAt: Long
        var newLastLoginAt = loginAt
        if (!hadRow || currentStreak == 0) {
            newStreak = 1
            newStartedAt = loginAt
        } else {
            var gap = today - lastDay
            if (gap < 0) {
                // A delayed write for an older streak day should never
                // rewind the authoritative latest qualified day.
                return StreakSnapshot(currentStreak, longestStreak, storedLastLoginAt, streakStartedAt)
            } else if (gap == 0L) {
                // Already qualified today — streak unchanged.
                newStreak = currentStreak
                newStartedAt = streakStartedAt
                newLastLoginAt = Math.max(storedLastLoginAt, loginAt)
            } else if (gap <= 2) {
                // Next day, or a single forgiven missed day — increment.
                newStreak = currentStreak + 1
                newStartedAt = streakStartedAt
            } else {
                // Two or more days missed — start over.
                newStreak = 1
                newStartedAt = loginAt
            }
        }
        var newLongest = Math.max(longestStreak, newStreak)

        // Alt accounts only ever hold a one-day streak at most.
        if (isAltAccount(conn, uuid)) {
            if (newStreak > 1) {
                newStreak = 1
                newStartedAt = loginAt
            }
            newLongest = Math.min(newLongest, 1)
        }

        conn.prepareStatement(UPSERT_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.setInt(2, newStreak)
            stmt.setInt(3, newLongest)
            stmt.setLong(4, newLastLoginAt)
            stmt.setLong(5, newStartedAt)
            stmt.executeUpdate()
        }

        return StreakSnapshot(newStreak, newLongest, newLastLoginAt, newStartedAt)
    }

    private fun isAltAccount(conn: Connection, uuid: String?): Boolean {
        conn.prepareStatement(IS_ALT_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.executeQuery().use { rs ->
                return rs.next()
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
    fun capAltStreak(uuid: String?) {
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(CAP_ALT_STREAK_SQL).use { stmt ->
                    stmt.setString(1, uuid)
                    var updated = stmt.executeUpdate()
                    if (updated > 0) {
                        logger.info("Capped login streak for alt account {}", uuid)
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to cap login streak for alt {}", uuid, e)
        }
    }

    fun recordPlaytime(uuid: String?, from: Long, to: Long): PlaytimeCreditResult? {
        if (to <= from) {
            return PlaytimeCreditResult(null, getQualificationProgress(uuid, to))
        }

        var latestSnapshot: StreakSnapshot? = null
        var progress: QualificationProgress? = null
        try {
            dataSource.getConnection().use { conn ->
                var oldAutoCommit = conn.getAutoCommit()
                conn.setAutoCommit(false)
                try {
                    var rh = resetHourUtc
                    var cursor = from
                    while (cursor < to) {
                        var day = dayNumber(cursor, rh)
                        var segmentEnd = Math.min(to, startOfDay(day + 1, rh))
                        var qualifiedAt = creditDay(conn, uuid, day, cursor, segmentEnd)
                        if (qualifiedAt > 0L) {
                            latestSnapshot = recordLoginAt(conn, uuid, qualifiedAt)
                        }
                        cursor = segmentEnd
                    }
                    progress = loadQualificationProgress(conn, uuid, to)
                    conn.commit()
                } catch (e: SQLException) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.setAutoCommit(oldAutoCommit)
                }
                return PlaytimeCreditResult(latestSnapshot, progress)
            }
        } catch (e: SQLException) {
            logger.error("Failed to record login streak playtime for {}", uuid, e)
            return null
        }
    }

    fun getQualificationProgress(uuid: String?): QualificationProgress? {
        var now = System.currentTimeMillis() / 1000L
        return getQualificationProgress(uuid, now)
    }

    fun getQualificationProgress(uuid: String?, at: Long): QualificationProgress? {
        try {
            dataSource.getConnection().use { conn ->
                return loadQualificationProgress(conn, uuid, at)
            }
        } catch (e: SQLException) {
            logger.error("Failed to read login streak progress for {}", uuid, e)
            return null
        }
    }

    private fun creditDay(conn: Connection, uuid: String?, streakDay: Long, segmentStart: Long, segmentEnd: Long): Long {
        var seconds = segmentEnd - segmentStart
        if (seconds <= 0L) return 0L

        ensureProgressRow(conn, uuid, streakDay)

        var accumulated: Int
        var qualifiedAt: Long
        conn.prepareStatement(SELECT_PROGRESS_FOR_UPDATE_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.setLong(2, streakDay)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    throw SQLException("Missing progress row after insert")
                }
                accumulated = rs.getInt("accumulated_seconds")
                qualifiedAt = nullableLong(rs, "qualified_at")
            }
        }

        if (qualifiedAt > 0L) return 0L

        val recordedAt = recordedLoginAtForDay(conn, uuid, streakDay)
        if (recordedAt != null) {
            updateProgress(conn, uuid, streakDay, accumulated, recordedAt)
            return 0L
        }

        var required = requiredPlaySeconds
        var newAccumulated = Math.min(Int.MAX_VALUE.toLong(), accumulated + seconds).toInt()
        var newQualifiedAt = 0L
        if (accumulated < required && newAccumulated >= required) {
            var crossedAt = segmentStart + (required - accumulated)
            newQualifiedAt = Math.min(crossedAt, segmentEnd - 1L)
        }

        updateProgress(conn, uuid, streakDay, newAccumulated, newQualifiedAt)
        return newQualifiedAt
    }

    private fun ensureProgressRow(conn: Connection, uuid: String?, streakDay: Long) {
        conn.prepareStatement(INSERT_PROGRESS_ROW_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.setLong(2, streakDay)
            stmt.executeUpdate()
        }
    }

    private fun updateProgress(conn: Connection, uuid: String?, streakDay: Long, accumulated: Int, qualifiedAt: Long) {
        conn.prepareStatement(UPDATE_PROGRESS_SQL).use { stmt ->
            stmt.setInt(1, accumulated)
            if (qualifiedAt > 0L) {
                stmt.setLong(2, qualifiedAt)
            } else {
                stmt.setNull(2, Types.INTEGER)
            }
            stmt.setString(3, uuid)
            stmt.setLong(4, streakDay)
            stmt.executeUpdate()
        }
    }

    private fun loadQualificationProgress(conn: Connection, uuid: String?, at: Long): QualificationProgress {
        var rh = resetHourUtc
        var day = dayNumber(at, rh)
        var accumulated = 0
        var qualifiedAt = 0L

        conn.prepareStatement(SELECT_PROGRESS_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.setLong(2, day)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    accumulated = rs.getInt("accumulated_seconds")
                    qualifiedAt = nullableLong(rs, "qualified_at")
                }
            }
        }

        val recordedAt = recordedLoginAtForDay(conn, uuid, day)
        if (recordedAt != null && qualifiedAt == 0L) {
            qualifiedAt = recordedAt
        }

        return QualificationProgress(day, accumulated, requiredPlaySeconds, qualifiedAt)
    }

    private fun recordedLoginAtForDay(conn: Connection, uuid: String?, streakDay: Long): Long? {
        conn.prepareStatement(SELECT_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                var lastLoginAt = rs.getLong("last_login_at")
                return if (dayNumber(lastLoginAt, resetHourUtc) == streakDay) lastLoginAt else null
            }
        }
    }

    private fun nullableLong(rs: ResultSet, column: String?): Long {
        var value = rs.getLong(column)
        return if (rs.wasNull()) 0L else value
    }

    fun get(uuid: String?): StreakSnapshot? {
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(SELECT_SQL).use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return null
                        return StreakSnapshot(
                            rs.getInt("current_streak"),
                            rs.getInt("longest_streak"),
                            rs.getLong("last_login_at"),
                            rs.getLong("streak_started_at"))
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to read login streak for {}", uuid, e)
            return null
        }
    }

    fun getPlayerStreakJson(uuid: String?): JsonObject? {
        val snap = get(uuid)
        if (snap == null) return null

        var now = System.currentTimeMillis() / 1000L
        var expiresAt = expiryOf(snap.lastLoginAt, resetHourUtc)
        var active = now < expiresAt

        var obj = JsonObject()
        obj.addProperty("uuid", uuid)
        obj.addProperty("current_streak", if (active) snap.currentStreak else 0)
        obj.addProperty("pending_streak", snap.currentStreak)
        obj.addProperty("longest_streak", snap.longestStreak)
        obj.addProperty("last_login_at", snap.lastLoginAt)
        obj.addProperty("streak_started_at", snap.streakStartedAt)
        obj.addProperty("expires_at", expiresAt)
        obj.addProperty("active", active)
        return obj
    }

    fun getLeaderboard(limit: Int, offset: Int, longest: Boolean): JsonObject {
        var safeLimit = Math.max(1, Math.min(100, limit))
        var safeOffset = Math.max(0, offset)
        var column = if (longest) "longest_streak" else "current_streak"

        var rh = resetHourUtc
        var now = System.currentTimeMillis() / 1000L
        // Streaks reset lazily (on the player's next qualified day), so the
        // stored current_streak of a long-absent player is stale. The current
        // leaderboard must drop lapsed streaks, or absent players keep their
        // rank indefinitely. last_login_at >= startOfDay(today - 2) is exactly
        // the "now < expiryOf(last_login_at)" liveness check in SQL form.
        var activeCutoff = startOfDay(dayNumber(now, rh) - 2, rh)
        var activeFilter = if (longest) "" else "AND s.last_login_at >= ? "

        // Alt accounts are excluded — they only ever hold a one-day streak
        // and should not occupy leaderboard ranks alongside main accounts.
        var notAlt = "AND NOT EXISTS (SELECT 1 FROM player_alts pa WHERE pa.minecraft_uuid = s.minecraft_uuid) "
        var isLeaderboardEligible = "AND EXISTS (SELECT 1 FROM players eligible_player " +
        "WHERE eligible_player.minecraft_uuid = s.minecraft_uuid " +
        "AND eligible_player.is_discord_member = true " +
        "AND eligible_player.last_mc_login_at >= " +
        "EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000) "

        var listSql = "SELECT s.minecraft_uuid, s.current_streak, s.longest_streak, " +
        "s.last_login_at, s.streak_started_at, p.minecraft_username " +
        "FROM player_login_streaks s " +
        "LEFT JOIN players p ON p.minecraft_uuid = s.minecraft_uuid " +
        "WHERE s." + column + " > 0 " + activeFilter + notAlt + isLeaderboardEligible +
        "ORDER BY s." + column + " DESC, s.last_login_at DESC " +
        "LIMIT ? OFFSET ?"
        var countSql = "SELECT COUNT(*) FROM player_login_streaks s " +
        "WHERE s." + column + " > 0 " + activeFilter + notAlt + isLeaderboardEligible

        var entries = JsonArray()
        var total = 0
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(countSql).use { stmt ->
                    if (!longest) stmt.setLong(1, activeCutoff)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) total = rs.getInt(1)
                    }
                }

                conn.prepareStatement(listSql).use { stmt ->
                    var param = 0
                    if (!longest) stmt.setLong(++param, activeCutoff)
                    stmt.setInt(++param, safeLimit)
                    stmt.setInt(++param, safeOffset)
                    stmt.executeQuery().use { rs ->
                        var rank = safeOffset
                        while (rs.next()) {
                            rank++
                            var currentStreak = rs.getInt("current_streak")
                            var lastLogin = rs.getLong("last_login_at")
                            var active = now < expiryOf(lastLogin, rh)
                            var entry = JsonObject()
                            entry.addProperty("rank", rank)
                            entry.addProperty("uuid", rs.getString("minecraft_uuid"))
                            entry.addProperty("username", rs.getString("minecraft_username"))
                            entry.addProperty("current_streak", if (active) currentStreak else 0)
                            entry.addProperty("pending_streak", currentStreak)
                            entry.addProperty("longest_streak", rs.getInt("longest_streak"))
                            entry.addProperty("last_login_at", lastLogin)
                            entry.addProperty("streak_started_at", rs.getLong("streak_started_at"))
                            entry.addProperty("active", active)
                            entries.add(entry)
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to read login streak leaderboard", e)
        }

        var response = JsonObject()
        response.addProperty("metric", if (longest) "longest" else "current")
        response.add("leaderboard", entries)
        response.addProperty("total", total)
        response.addProperty("offset", safeOffset)
        response.addProperty("limit", safeLimit)
        return response
    }

    class StreakSnapshot(@JvmField val currentStreak: Int, @JvmField val longestStreak: Int,
        @JvmField val lastLoginAt: Long, @JvmField val streakStartedAt: Long)

    class QualificationProgress(@JvmField val streakDay: Long, @JvmField val accumulatedSeconds: Int,
        @JvmField val requiredSeconds: Int, @JvmField val qualifiedAt: Long) {
        @JvmField val qualified: Boolean = qualifiedAt > 0L
        fun remainingSeconds(): Int {
            if (qualified) return 0
            return Math.max(0, requiredSeconds - accumulatedSeconds)
        }
    }

    class PlaytimeCreditResult(@JvmField val streakSnapshot: StreakSnapshot?, @JvmField val progress: QualificationProgress?)

    companion object {
        const val DEFAULT_RESET_HOUR_UTC = 6
        const val DEFAULT_REQUIRED_PLAY_MINUTES = 10
        private const val DAY_SECONDS = 86_400L
        private const val SECONDS_PER_MINUTE = 60
        @JvmStatic fun minutesToSeconds(minutes: Int): Int = Math.max(1, minutes) * SECONDS_PER_MINUTE
        private fun clampResetHour(resetHourUtc: Int): Int {
            if (resetHourUtc < 0) return 0
            if (resetHourUtc > 23) return 23
            return resetHourUtc
        }
        private fun dayNumber(epochSeconds: Long, resetHourUtc: Int): Long =
        Math.floorDiv(epochSeconds - resetHourUtc * 3600L, DAY_SECONDS)
        private fun startOfDay(streakDay: Long, resetHourUtc: Int): Long = streakDay * DAY_SECONDS + resetHourUtc * 3600L

        /** The start of the third streak-day after the last qualified day. */
        @JvmStatic fun expiryOf(lastLoginAt: Long, resetHourUtc: Int): Long {
            val lastDay = dayNumber(lastLoginAt, resetHourUtc)
            return (lastDay + 3) * DAY_SECONDS + resetHourUtc * 3600L
        }
        private val CREATE_TABLE_SQL = """
                CREATE TABLE IF NOT EXISTS player_login_streaks (
                    minecraft_uuid TEXT PRIMARY KEY,
                    current_streak INTEGER NOT NULL DEFAULT 0,
                    longest_streak INTEGER NOT NULL DEFAULT 0,
                    last_login_at INTEGER NOT NULL,
                    streak_started_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent() + "\n"

        private val CREATE_CURRENT_IDX_SQL =
        "CREATE INDEX IF NOT EXISTS pls_current_streak_idx ON player_login_streaks (current_streak)"
        private val CREATE_LONGEST_IDX_SQL =
        "CREATE INDEX IF NOT EXISTS pls_longest_streak_idx ON player_login_streaks (longest_streak)"

        private val CREATE_PROGRESS_TABLE_SQL = """
                CREATE TABLE IF NOT EXISTS player_login_streak_progress (
                    minecraft_uuid TEXT NOT NULL,
                    streak_day BIGINT NOT NULL,
                    accumulated_seconds INTEGER NOT NULL DEFAULT 0,
                    qualified_at INTEGER,
                    updated_at INTEGER NOT NULL,
                    CONSTRAINT player_login_streak_progress_minecraft_uuid_streak_day_pk PRIMARY KEY (minecraft_uuid, streak_day)
                )
                """.trimIndent() + "\n"

        private val SELECT_SQL =
        "SELECT current_streak, longest_streak, last_login_at, streak_started_at " +
        "FROM player_login_streaks WHERE minecraft_uuid = ?"

        private val SELECT_PROGRESS_SQL =
        "SELECT accumulated_seconds, qualified_at " +
        "FROM player_login_streak_progress WHERE minecraft_uuid = ? AND streak_day = ?"

        private val SELECT_PROGRESS_FOR_UPDATE_SQL =
        SELECT_PROGRESS_SQL + " FOR UPDATE"

        private val INSERT_PROGRESS_ROW_SQL = """
                INSERT INTO player_login_streak_progress
                    (minecraft_uuid, streak_day, accumulated_seconds, updated_at)
                VALUES (?, ?, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
                ON CONFLICT (minecraft_uuid, streak_day) DO NOTHING
                """.trimIndent() + "\n"

        private val UPDATE_PROGRESS_SQL = """
                UPDATE player_login_streak_progress SET
                    accumulated_seconds = ?,
                    qualified_at = ?,
                    updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER
                WHERE minecraft_uuid = ? AND streak_day = ?
                """.trimIndent() + "\n"

        private val IS_ALT_SQL =
        "SELECT 1 FROM player_alts WHERE minecraft_uuid = ? LIMIT 1"

        private val CAP_ALT_STREAK_SQL = """
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
                """.trimIndent() + "\n"

        private val UPSERT_SQL = """
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
                """.trimIndent() + "\n"
    }
}
