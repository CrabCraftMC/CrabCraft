package crabcraft.net.crabUtilities.velocity.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.SQLException

open class PostgresStatsWriter(jdbcUrl: String?, username: String?, password: String?, private val logger: Logger) {
    enum class NicknameLoadStatus { FOUND, ABSENT, FAILED }

    data class NicknameLoadResult(val status: NicknameLoadStatus, val rawNickname: String?) {
        fun status(): NicknameLoadStatus = status
        fun rawNickname(): String? = rawNickname
        companion object {
            @JvmStatic fun found(rawNickname: String?): NicknameLoadResult = NicknameLoadResult(NicknameLoadStatus.FOUND, rawNickname)
            @JvmStatic fun absent(): NicknameLoadResult = NicknameLoadResult(NicknameLoadStatus.ABSENT, null)
            @JvmStatic fun failed(): NicknameLoadResult = NicknameLoadResult(NicknameLoadStatus.FAILED, null)
        }
    }

    private val dataSource: HikariDataSource
    init {
        val config = HikariConfig()
        config.jdbcUrl = jdbcUrl
        config.username = username
        config.password = password
        config.maximumPoolSize = 10
        config.connectionTimeout = 5000
        config.poolName = "CrabUtilities-PG"
        // Velocity isolates classloaders: name the bundled JDBC driver explicitly.
        config.driverClassName = "org.postgresql.Driver"
        dataSource = HikariDataSource(config)
        ensurePlayerAwardsSchema()
        GallerySchema.ensure(dataSource, logger)
        WebToolSchema.ensure(dataSource, logger)
    }
    private fun ensurePlayerAwardsSchema() {
        // Keep this column synchronised with packages/db/src/schema.ts.
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement("""
                                     ALTER TABLE players ADD COLUMN IF NOT EXISTS
                                         awards_excluded BOOLEAN NOT NULL DEFAULT FALSE
                                     """ .trimIndent() + "\n").use { stmt ->
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            dataSource.close()
            throw IllegalStateException("Failed to initialise player award exclusions", e)
        }
    }

    open fun writePlayerSeasonStats(uuid: String?, season: String?, stats: ComputedStats) {
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(UPSERT_SQL).use { stmt ->
                    var i = 1
                    stmt.setString(i++, uuid)
                    stmt.setString(i++, season)
                    stmt.setInt(i++, stats.playTimeSeconds)
                    stmt.setDouble(i++, stats.walkDistanceM)
                    stmt.setDouble(i++, stats.sprintDistanceM)
                    stmt.setDouble(i++, stats.swimDistanceM)
                    stmt.setDouble(i++, stats.flyDistanceM)
                    stmt.setDouble(i++, stats.boatDistanceM)
                    stmt.setDouble(i++, stats.elytraDistanceM)
                    stmt.setDouble(i++, stats.horseDistanceM)
                    stmt.setDouble(i++, stats.climbDistanceM)
                    stmt.setDouble(i++, stats.fallDistanceM)
                    stmt.setDouble(i++, stats.totalDistanceM)
                    stmt.setInt(i++, stats.mobKills)
                    stmt.setInt(i++, stats.playerKills)
                    stmt.setInt(i++, stats.deaths)
                    stmt.setInt(i++, stats.damageDealt)
                    stmt.setInt(i++, stats.damageTaken)
                    stmt.setInt(i++, stats.totalBlocksMined)
                    stmt.setInt(i++, stats.totalBlocksPlaced)
                    stmt.setInt(i++, stats.totalItemsCrafted)
                    stmt.setInt(i++, stats.totalItemsBroken)
                    stmt.setInt(i++, stats.jumps)
                    stmt.setInt(i++, stats.animalsBred)
                    stmt.setInt(i++, stats.fishCaught)
                    stmt.setInt(i++, stats.villagerTraded)
                    stmt.setInt(i++, stats.enchantments)
                    stmt.setInt(i++, stats.timesSlept)
                    stmt.setString(i++, stats.topBlockMined)
                    stmt.setString(i++, stats.topMobKilled)
                    stmt.setString(i++, stats.topItemCrafted)
                    stmt.setString(i++, stats.topItemUsed)
                    stmt.setString(i++, stats.topDeathCause)
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to write player season stats for {}", uuid, e)
        }
    }

    /**
     * Updates player info (username + nickname) by minecraft_uuid.
     * Only updates existing players — no-op if UUID not in DB.
     */
    open fun upsertPlayer(uuid: String?, username: String?, nickname: String?, nicknameRaw: String?): Boolean {
        var selectSql = "SELECT last_mc_login_at FROM players WHERE minecraft_uuid = ? FOR UPDATE"
        var updateSql = "UPDATE players SET minecraft_username = ?, nickname = ?, nickname_raw = ?, " +
        "updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER, " +
        "last_mc_login_at = EXTRACT(EPOCH FROM NOW())::INTEGER " +
        "WHERE minecraft_uuid = ?"
        try {
            dataSource.getConnection().use { conn ->
                conn.setAutoCommit(false)
                try {
                    var wasInactive = false
                    conn.prepareStatement(selectSql).use { stmt ->
                        stmt.setString(1, uuid)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                var storedLastLoginAt = rs.getLong("last_mc_login_at")
                                val lastLoginAt = if (rs.wasNull()) null else storedLastLoginAt
                                wasInactive = isInactiveForLeaderboard(
                                    lastLoginAt, System.currentTimeMillis() / 1000L)
                            }
                        }
                    }

                    conn.prepareStatement(updateSql).use { stmt ->
                        stmt.setString(1, username)
                        stmt.setString(2, nickname)
                        stmt.setString(3, nicknameRaw)
                        stmt.setString(4, uuid)
                        stmt.executeUpdate()
                    }
                    conn.commit()
                    return wasInactive
                } catch (e: SQLException) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.setAutoCommit(true)
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to upsert player {}", uuid, e)
            return false
        }
    }

    /**
     * Updates alt account username by minecraft_uuid if it exists in player_alts.
     */
    open fun upsertAltUsername(uuid: String?, username: String?) {
        var sql = "UPDATE player_alts SET minecraft_username = ? WHERE minecraft_uuid = ?"
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, username)
                    stmt.setString(2, uuid)
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to update alt username for {}", uuid, e)
        }
    }

    /**
     * Check if a player has ever logged into Minecraft. Looks at:
     *   - mc_login_history (covers every player, including unverified)
     *   - players.last_mc_login_at (legacy, kept for safety)
     *   - player_alts (alt accounts linked via Discord)
     *
     * Fail-safe: on any SQLException (pool exhaustion, slow query, DB
     * outage) this returns {@code true}, so the join broadcaster falls
     * through to the regular "joined the game" message rather than
     * wrongly announcing a returning player as a first-time visitor.
     */
    open fun hasJoinedBefore(uuid: String?): Boolean {
        var sql = """
                SELECT 1 FROM mc_login_history WHERE minecraft_uuid = ?
                UNION ALL
                SELECT 1 FROM players WHERE minecraft_uuid = ? AND last_mc_login_at IS NOT NULL
                UNION ALL
                SELECT 1 FROM player_alts WHERE minecraft_uuid = ?
                LIMIT 1
                """ .trimIndent() + "\n"
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.setString(2, uuid)
                    stmt.setString(3, uuid)
                    stmt.executeQuery().use { rs ->
                        return rs.next()
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to check join status for {} — defaulting to 'joined before' to avoid wrongly announcing them as a first-time player", uuid, e)
            return true
        }
    }

    /**
     * Records a Minecraft login in mc_login_history. Inserts on first
     * sight, otherwise bumps last_seen_at. Independent of Discord
     * verification — every connecting UUID is tracked here so the
     * "first join" check works for unverified players.
     */
    open fun recordMcLogin(uuid: String?) {
        var sql = """
                INSERT INTO mc_login_history (minecraft_uuid, first_seen_at, last_seen_at)
                VALUES (?, EXTRACT(EPOCH FROM NOW())::INTEGER, EXTRACT(EPOCH FROM NOW())::INTEGER)
                ON CONFLICT (minecraft_uuid) DO UPDATE SET
                    last_seen_at = EXTRACT(EPOCH FROM NOW())::INTEGER
                """ .trimIndent() + "\n"
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to record mc login for {}", uuid, e)
        }
    }

    /**
     * Updates just the nickname for a player by minecraft_uuid.
     */
    open fun updateNickname(uuid: String?, nickname: String?, nicknameRaw: String?) {
        var sql = "UPDATE players SET nickname = ?, nickname_raw = ?, updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER WHERE minecraft_uuid = ?"
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, nickname)
                    stmt.setString(2, nicknameRaw)
                    stmt.setString(3, uuid)
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to update nickname for {}", uuid, e)
        }
    }

    /**
     * Loads the stored raw nickname for a player by minecraft_uuid, keeping a
     * missing nickname distinct from a database failure.
     */
    open fun loadRawNickname(uuid: String?): NicknameLoadResult {
        var sql = "SELECT nickname_raw FROM players WHERE minecraft_uuid = ?"
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            var raw = rs.getString(1)
                            return if (raw == null || raw.isEmpty()) NicknameLoadResult.absent() else NicknameLoadResult.found(raw)
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to load nickname for {}", uuid, e)
            return NicknameLoadResult.failed()
        }
        return NicknameLoadResult.absent()
    }

    /**
     * Shared HikariCP pool. Exposed so auxiliary writers (awards, crown
     * scores) can reuse the same connection pool rather than opening their
     * own.
     */
    open fun getDataSource(): HikariDataSource {
        return dataSource
    }

    open fun close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close()
        }
    }

    companion object {
        private const val LEADERBOARD_ACTIVITY_WINDOW_SECONDS = 30L * 24L * 60L * 60L
        @JvmStatic fun isInactiveForLeaderboard(lastLoginAt: Long?, now: Long): Boolean =
        lastLoginAt == null || lastLoginAt < now - LEADERBOARD_ACTIVITY_WINDOW_SECONDS
        private val UPSERT_SQL = """
        INSERT INTO player_season_stats (
            minecraft_uuid, season,
            play_time_seconds, walk_distance_m, sprint_distance_m,
            swim_distance_m, fly_distance_m, boat_distance_m,
            elytra_distance_m, horse_distance_m, climb_distance_m,
            fall_distance_m, total_distance_m,
            mob_kills, player_kills, deaths,
            damage_dealt, damage_taken,
            total_blocks_mined, total_blocks_placed,
            total_items_crafted, total_items_broken,
            jumps, animals_bred, fish_caught,
            villagers_traded, enchantments, times_slept,
            top_block_mined, top_mob_killed, top_item_crafted,
            top_item_used, top_death_cause,
            computed_at
        ) VALUES (
            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
            EXTRACT(EPOCH FROM NOW())::INTEGER
        )
        ON CONFLICT (minecraft_uuid, season) DO UPDATE SET
            play_time_seconds = EXCLUDED.play_time_seconds,
            walk_distance_m = EXCLUDED.walk_distance_m,
            sprint_distance_m = EXCLUDED.sprint_distance_m,
            swim_distance_m = EXCLUDED.swim_distance_m,
            fly_distance_m = EXCLUDED.fly_distance_m,
            boat_distance_m = EXCLUDED.boat_distance_m,
            elytra_distance_m = EXCLUDED.elytra_distance_m,
            horse_distance_m = EXCLUDED.horse_distance_m,
            climb_distance_m = EXCLUDED.climb_distance_m,
            fall_distance_m = EXCLUDED.fall_distance_m,
            total_distance_m = EXCLUDED.total_distance_m,
            mob_kills = EXCLUDED.mob_kills,
            player_kills = EXCLUDED.player_kills,
            deaths = EXCLUDED.deaths,
            damage_dealt = EXCLUDED.damage_dealt,
            damage_taken = EXCLUDED.damage_taken,
            total_blocks_mined = EXCLUDED.total_blocks_mined,
            total_blocks_placed = EXCLUDED.total_blocks_placed,
            total_items_crafted = EXCLUDED.total_items_crafted,
            total_items_broken = EXCLUDED.total_items_broken,
            jumps = EXCLUDED.jumps,
            animals_bred = EXCLUDED.animals_bred,
            fish_caught = EXCLUDED.fish_caught,
            villagers_traded = EXCLUDED.villagers_traded,
            enchantments = EXCLUDED.enchantments,
            times_slept = EXCLUDED.times_slept,
            top_block_mined = EXCLUDED.top_block_mined,
            top_mob_killed = EXCLUDED.top_mob_killed,
            top_item_crafted = EXCLUDED.top_item_crafted,
            top_item_used = EXCLUDED.top_item_used,
            top_death_cause = EXCLUDED.top_death_cause,
            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
        """.trimIndent() + "\n"
    }
}
