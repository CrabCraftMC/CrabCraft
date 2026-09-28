package crabcraft.net.crabUtilities.velocity.db

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import org.slf4j.Logger

/**
 * Tracks qualified playtime in fixed UTC streak-days starting at the configured reset hour. One missed day is forgiven;
 * two missed days reset the next qualified streak to one. Alt accounts are capped at one day and excluded from the
 * streak leaderboard.
 */
class LoginStreakService {
    private val dataSource: HikariDataSource

    private val logger: Logger

    private val resetHourUtc: Int

    private val requiredPlaySeconds: Int

    constructor(dataSource: HikariDataSource, logger: Logger, resetHourUtc: Int, requiredPlaySeconds: Int) {
        this.dataSource = dataSource
        this.logger = logger
        this.resetHourUtc = clampResetHour(resetHourUtc)
        this.requiredPlaySeconds = Math.max(1, requiredPlaySeconds)
        ensureSchema()
    }

    fun getResetHourUtc(): Int {
        return resetHourUtc
    }

    fun getRequiredPlaySeconds(): Int {
        return requiredPlaySeconds
    }

    fun secondsUntilNextStreakDay(epochSeconds: Long): Long {
        val rh: Int = resetHourUtc
        val today: Long = dayNumber(epochSeconds, rh)
        return Math.max(0L, startOfDay(today + 1, rh) - epochSeconds)
    }

    private fun ensureSchema() {
        try {
            dataSource.connection.use { conn ->
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

    private fun recordLoginAt(conn: Connection, uuid: String, loginAt: Long): StreakSnapshot {
        val rh: Int = resetHourUtc
        val today: Long = dayNumber(loginAt, rh)

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
        var newLastLoginAt: Long = loginAt
        if (!hadRow || currentStreak == 0) {
            newStreak = 1
            newStartedAt = loginAt
        } else {
            val gap: Long = today - lastDay
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
        var newLongest: Int = Math.max(longestStreak, newStreak)

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

    /**
     * Clamps an alt account's stored streak to a single day. Called when the proxy identifies a connecting player as an
     * alt, so streaks accumulated before the account was registered as an alt (or before alt capping existed) are
     * normalized without waiting for the next qualified day.
     */
    fun capAltStreak(uuid: String) {
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(CAP_ALT_STREAK_SQL).use { stmt ->
                    stmt.setString(1, uuid)
                    val updated: Int = stmt.executeUpdate()
                    if (updated > 0) {
                        logger.info("Capped login streak for alt account {}", uuid)
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to cap login streak for alt {}", uuid, e)
        }
    }

    fun recordPlaytime(uuid: String, from: Long, to: Long): PlaytimeCreditResult? {
        if (to <= from) {
            return PlaytimeCreditResult(null, getQualificationProgress(uuid, to))
        }

        var latestSnapshot: StreakSnapshot? = null
        var progress: QualificationProgress? = null
        try {
            dataSource.connection.use { conn ->
                val oldAutoCommit: Boolean = conn.autoCommit
                conn.autoCommit = false
                try {
                    val rh: Int = resetHourUtc
                    var cursor: Long = from
                    while (cursor < to) {
                        val day: Long = dayNumber(cursor, rh)
                        val segmentEnd: Long = Math.min(to, startOfDay(day + 1, rh))
                        var qualifiedAt: Long = creditDay(conn, uuid, day, cursor, segmentEnd)
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
                    conn.autoCommit = oldAutoCommit
                }
                return PlaytimeCreditResult(latestSnapshot, progress)
            }
        } catch (e: SQLException) {
            logger.error("Failed to record login streak playtime for {}", uuid, e)
            return null
        }
    }

    fun getQualificationProgress(uuid: String): QualificationProgress? {
        val now: Long = System.currentTimeMillis() / 1000L
        return getQualificationProgress(uuid, now)
    }

    fun getQualificationProgress(uuid: String, at: Long): QualificationProgress? {
        try {
            dataSource.connection.use { conn ->
                return loadQualificationProgress(conn, uuid, at)
            }
        } catch (e: SQLException) {
            logger.error("Failed to read login streak progress for {}", uuid, e)
            return null
        }
    }

    private fun creditDay(conn: Connection, uuid: String, streakDay: Long, segmentStart: Long, segmentEnd: Long): Long {
        val seconds: Long = segmentEnd - segmentStart
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

        val required: Int = requiredPlaySeconds
        val newAccumulated: Int = Math.min(Int.MAX_VALUE.toLong(), accumulated + seconds).toInt()
        var newQualifiedAt: Long = 0L
        if (accumulated < required && newAccumulated >= required) {
            val crossedAt: Long = segmentStart + (required - accumulated)
            newQualifiedAt = Math.min(crossedAt, segmentEnd - 1L)
        }

        updateProgress(conn, uuid, streakDay, newAccumulated, newQualifiedAt)
        return newQualifiedAt
    }

    private fun ensureProgressRow(conn: Connection, uuid: String, streakDay: Long) {
        conn.prepareStatement(INSERT_PROGRESS_ROW_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.setLong(2, streakDay)
            stmt.executeUpdate()
        }
    }

    private fun updateProgress(conn: Connection, uuid: String, streakDay: Long, accumulated: Int, qualifiedAt: Long) {
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

    private fun loadQualificationProgress(conn: Connection, uuid: String, at: Long): QualificationProgress {
        val rh: Int = resetHourUtc
        val day: Long = dayNumber(at, rh)
        var accumulated: Int = 0
        var qualifiedAt: Long = 0L

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

        if (qualifiedAt == 0L) {
            val recordedAt = recordedLoginAtForDay(conn, uuid, day)
            if (recordedAt != null) qualifiedAt = recordedAt
        }

        return QualificationProgress(day, accumulated, requiredPlaySeconds, qualifiedAt)
    }

    private fun recordedLoginAtForDay(conn: Connection, uuid: String, streakDay: Long): Long? {
        conn.prepareStatement(SELECT_SQL).use { stmt ->
            stmt.setString(1, uuid)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                val lastLoginAt: Long = rs.getLong("last_login_at")
                return if (dayNumber(lastLoginAt, resetHourUtc) == streakDay) lastLoginAt else null
            }
        }
    }

    fun get(uuid: String): StreakSnapshot? {
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_SQL).use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return null
                        return StreakSnapshot(
                            rs.getInt("current_streak"),
                            rs.getInt("longest_streak"),
                            rs.getLong("last_login_at"),
                            rs.getLong("streak_started_at"),
                        )
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to read login streak for {}", uuid, e)
            return null
        }
    }

    fun getPlayerStreakJson(uuid: String): JsonObject? {
        val snap = get(uuid)
        if (snap == null) return null

        val now: Long = System.currentTimeMillis() / 1000L
        val expiresAt: Long = expiryOf(snap.lastLoginAt, resetHourUtc)
        val active: Boolean = now < expiresAt

        val obj: JsonObject = JsonObject()
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
        val safeLimit: Int = Math.max(1, Math.min(100, limit))
        val safeOffset: Int = Math.max(0, offset)
        val column: String = if (longest) "longest_streak" else "current_streak"

        val rh: Int = resetHourUtc
        val now: Long = System.currentTimeMillis() / 1000L
        // Streaks reset lazily (on the player's next qualified day), so the
        // stored current_streak of a long-absent player is stale. The current
        // leaderboard must drop lapsed streaks, or absent players keep their
        // rank indefinitely. last_login_at >= startOfDay(today - 2) is exactly
        // the "now < expiryOf(last_login_at)" liveness check in SQL form.
        val activeCutoff: Long = startOfDay(dayNumber(now, rh) - 2, rh)
        val activeFilter: String = if (longest) "" else "AND s.last_login_at >= ? "

        // Alt accounts are excluded — they only ever hold a one-day streak
        // and should not occupy leaderboard ranks alongside main accounts.
        val notAlt: String = "AND NOT EXISTS (SELECT 1 FROM player_alts pa WHERE pa.minecraft_uuid = s.minecraft_uuid) "
        val isLeaderboardEligible: String =
            "AND EXISTS (SELECT 1 FROM players eligible_player " +
                "WHERE eligible_player.minecraft_uuid = s.minecraft_uuid " +
                "AND eligible_player.is_discord_member = true " +
                "AND eligible_player.last_mc_login_at >= " +
                "EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000) "

        val listSql: String =
            "SELECT s.minecraft_uuid, s.current_streak, s.longest_streak, " +
                "s.last_login_at, s.streak_started_at, p.minecraft_username " +
                "FROM player_login_streaks s " +
                "LEFT JOIN players p ON p.minecraft_uuid = s.minecraft_uuid " +
                "WHERE s." +
                column +
                " > 0 " +
                activeFilter +
                notAlt +
                isLeaderboardEligible +
                "ORDER BY s." +
                column +
                " DESC, s.last_login_at DESC " +
                "LIMIT ? OFFSET ?"
        val countSql: String =
            "SELECT COUNT(*) FROM player_login_streaks s " +
                "WHERE s." +
                column +
                " > 0 " +
                activeFilter +
                notAlt +
                isLeaderboardEligible

        val entries: JsonArray = JsonArray()
        var total: Int = 0
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(countSql).use { stmt ->
                    if (!longest) stmt.setLong(1, activeCutoff)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) total = rs.getInt(1)
                    }
                }

                conn.prepareStatement(listSql).use { stmt ->
                    var param: Int = 0
                    if (!longest) stmt.setLong(++param, activeCutoff)
                    stmt.setInt(++param, safeLimit)
                    stmt.setInt(++param, safeOffset)
                    stmt.executeQuery().use { rs ->
                        var rank: Int = safeOffset
                        while (rs.next()) {
                            rank++
                            var currentStreak: Int = rs.getInt("current_streak")
                            val lastLogin: Long = rs.getLong("last_login_at")
                            val active: Boolean = now < expiryOf(lastLogin, rh)
                            val entry: JsonObject = JsonObject()
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

        val response: JsonObject = JsonObject()
        response.addProperty("metric", if (longest) "longest" else "current")
        response.add("leaderboard", entries)
        response.addProperty("total", total)
        response.addProperty("offset", safeOffset)
        response.addProperty("limit", safeLimit)
        return response
    }

    class StreakSnapshot(
        @JvmField val currentStreak: Int,
        @JvmField val longestStreak: Int,
        @JvmField val lastLoginAt: Long,
        @JvmField val streakStartedAt: Long,
    )

    class QualificationProgress(
        @JvmField val streakDay: Long,
        @JvmField val accumulatedSeconds: Int,
        @JvmField val requiredSeconds: Int,
        @JvmField val qualifiedAt: Long,
    ) {
        @JvmField val qualified = qualifiedAt > 0L

        fun remainingSeconds(): Int = if (qualified) 0 else Math.max(0, requiredSeconds - accumulatedSeconds)
    }

    class PlaytimeCreditResult(
        @JvmField val streakSnapshot: StreakSnapshot?,
        @JvmField val progress: QualificationProgress?,
    )

    companion object {
        const val DEFAULT_RESET_HOUR_UTC: Int = 6

        const val DEFAULT_REQUIRED_PLAY_MINUTES: Int = 10

        private val DAY_SECONDS: Long = 86_400L

        private val SECONDS_PER_MINUTE: Int = 60

        private val CREATE_TABLE_SQL: String =
            ("""
            CREATE TABLE IF NOT EXISTS player_login_streaks (
                minecraft_uuid TEXT PRIMARY KEY,
                current_streak INTEGER NOT NULL DEFAULT 0,
                longest_streak INTEGER NOT NULL DEFAULT 0,
                last_login_at INTEGER NOT NULL,
                streak_started_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """
                .trimIndent() + "\n")

        private val CREATE_CURRENT_IDX_SQL: String =
            "CREATE INDEX IF NOT EXISTS pls_current_streak_idx ON player_login_streaks (current_streak)"

        private val CREATE_LONGEST_IDX_SQL: String =
            "CREATE INDEX IF NOT EXISTS pls_longest_streak_idx ON player_login_streaks (longest_streak)"

        private val CREATE_PROGRESS_TABLE_SQL: String =
            ("""
            CREATE TABLE IF NOT EXISTS player_login_streak_progress (
                minecraft_uuid TEXT NOT NULL,
                streak_day BIGINT NOT NULL,
                accumulated_seconds INTEGER NOT NULL DEFAULT 0,
                qualified_at INTEGER,
                updated_at INTEGER NOT NULL,
                CONSTRAINT player_login_streak_progress_minecraft_uuid_streak_day_pk PRIMARY KEY (minecraft_uuid, streak_day)
            )
            """
                .trimIndent() + "\n")

        private val SELECT_SQL: String =
            "SELECT current_streak, longest_streak, last_login_at, streak_started_at " +
                "FROM player_login_streaks WHERE minecraft_uuid = ?"

        private val SELECT_PROGRESS_SQL: String =
            "SELECT accumulated_seconds, qualified_at " +
                "FROM player_login_streak_progress WHERE minecraft_uuid = ? AND streak_day = ?"

        private val SELECT_PROGRESS_FOR_UPDATE_SQL: String = SELECT_PROGRESS_SQL + " FOR UPDATE"

        private val INSERT_PROGRESS_ROW_SQL: String =
            ("""
            INSERT INTO player_login_streak_progress
                (minecraft_uuid, streak_day, accumulated_seconds, updated_at)
            VALUES (?, ?, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
            ON CONFLICT (minecraft_uuid, streak_day) DO NOTHING
            """
                .trimIndent() + "\n")

        private val UPDATE_PROGRESS_SQL: String =
            ("""
            UPDATE player_login_streak_progress SET
                accumulated_seconds = ?,
                qualified_at = ?,
                updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER
            WHERE minecraft_uuid = ? AND streak_day = ?
            """
                .trimIndent() + "\n")

        private val IS_ALT_SQL: String = "SELECT 1 FROM player_alts WHERE minecraft_uuid = ? LIMIT 1"

        private val CAP_ALT_STREAK_SQL: String =
            ("""
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
            """
                .trimIndent() + "\n")

        private val UPSERT_SQL: String =
            ("""
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
            """
                .trimIndent() + "\n")

        @JvmStatic
        fun minutesToSeconds(minutes: Int): Int {
            return Math.max(1, minutes) * SECONDS_PER_MINUTE
        }

        private fun clampResetHour(resetHourUtc: Int): Int {
            if (resetHourUtc < 0) return 0
            if (resetHourUtc > 23) return 23
            return resetHourUtc
        }

        /** The streak-day number a Unix timestamp falls in, given the reset hour. */
        private fun dayNumber(epochSeconds: Long, resetHourUtc: Int): Long {
            return Math.floorDiv(epochSeconds - resetHourUtc * 3600L, DAY_SECONDS)
        }

        private fun startOfDay(streakDay: Long, resetHourUtc: Int): Long {
            return streakDay * DAY_SECONDS + resetHourUtc * 3600L
        }

        /**
         * Unix second at which a streak lapses: the start of the third streak-day after the last qualified day.
         * Qualifying the next day (gap 1) or the day after (gap 2, one forgiven miss) keeps the streak alive; once this
         * instant passes, the next login resets the streak to 1.
         */
        @JvmStatic
        fun expiryOf(lastLoginAt: Long, resetHourUtc: Int): Long {
            var lastDay: Long = dayNumber(lastLoginAt, resetHourUtc)
            return (lastDay + 3) * DAY_SECONDS + resetHourUtc * 3600L
        }

        private fun isAltAccount(conn: Connection, uuid: String): Boolean {
            conn.prepareStatement(IS_ALT_SQL).use { stmt ->
                stmt.setString(1, uuid)
                stmt.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }

        private fun nullableLong(rs: ResultSet, column: String): Long {
            val value: Long = rs.getLong(column)
            return if (rs.wasNull()) 0L else value
        }
    }
}
