package crabcraft.net.crabUtilities.velocity.advancements

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariDataSource
import crabcraft.net.crabUtilities.velocity.api.LeaderboardIdentity
import java.sql.Array
import java.sql.SQLException
import java.util.HashMap
import org.slf4j.Logger

class AdvancementQueryService(
    private val dataSource: HikariDataSource,
    private val logger: Logger,
    private val registry: AdvancementRegistry,
) {

    private fun resolveSeason(seasonParam: String?): String? {
        if (seasonParam != null && !seasonParam.isEmpty()) return seasonParam
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT id FROM seasons WHERE is_current = true LIMIT 1").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        return if (rs.next()) rs.getString("id") else null
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to resolve current season", e)
            return null
        }
    }

    fun getPlayerAdvancements(uuid: String, seasonParam: String?): JsonObject? {
        val season = resolveSeason(seasonParam)
        if (season == null) return null

        try {
            dataSource.connection.use { conn ->
                var username: String? = null
                conn.prepareStatement("SELECT minecraft_username FROM players WHERE minecraft_uuid = ? LIMIT 1").use {
                    stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) username = rs.getString("minecraft_username")
                    }
                }

                val completionStatus: MutableMap<String, Boolean> = HashMap()
                val timestamps: MutableMap<String, Int> = HashMap()
                conn
                    .prepareStatement(
                        ("""
                        SELECT advancement_id, completed, completed_at
                        FROM player_advancements
                        WHERE minecraft_uuid = ? AND season = ?
                          AND advancement_id NOT LIKE 'minecraft:recipes/%'
                        """
                            .trimIndent() + "\n")
                    )
                    .use { stmt ->
                        stmt.setString(1, uuid)
                        stmt.setString(2, season)
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val advId: String = rs.getString("advancement_id")
                                completionStatus.put(advId, rs.getBoolean("completed"))
                                val completedAt: Int = rs.getInt("completed_at")
                                if (!rs.wasNull()) {
                                    timestamps.put(advId, completedAt)
                                }
                            }
                        }
                    }

                val advancements: JsonObject = JsonObject()
                var completed: Int = 0

                for (regEntry in registry.getAll().entries) {
                    val advId: String = regEntry.key
                    val meta: JsonObject = regEntry.value

                    val done: Boolean = completionStatus.get(advId) == true
                    if (done) completed++

                    val entry: JsonObject = JsonObject()
                    entry.addProperty("name", meta.get("name").getAsString())
                    entry.addProperty("description", meta.get("description").getAsString())
                    entry.addProperty("category", meta.get("category").getAsString())
                    entry.addProperty("completed", done)
                    entry.addProperty("completed_at", timestamps.get(advId))
                    advancements.add(advId, entry)
                }

                val response: JsonObject = JsonObject()
                response.addProperty("uuid", uuid)
                response.addProperty("username", username)
                response.addProperty("completed", completed)
                response.addProperty("total", registry.getTotal())
                response.add("advancements", advancements)
                return response
            }
        } catch (e: SQLException) {
            logger.error("Failed to load advancements for uuid={}", uuid, e)
            return null
        }
    }

    fun getAdvancementLeaderboard(seasonParam: String?, limit: Int, offset: Int, category: String?): JsonObject? {
        return getAdvancementLeaderboard(seasonParam, limit, offset, category, false)
    }

    fun getAdvancementLeaderboard(
        seasonParam: String?,
        limit: Int,
        offset: Int,
        category: String?,
        showHidden: Boolean,
    ): JsonObject? {
        var limit = limit
        var offset = offset
        val season = resolveSeason(seasonParam)
        if (season == null) return null
        if (limit <= 0 || limit > 100) limit = 100
        if (offset < 0) offset = 0

        val validCategory: Boolean = registry.isValidCategory(category)
        val categoryPrefix = if (validCategory) "minecraft:" + category + "/" else null
        val registeredAdvancementIds =
            registry.getAll().keys.filter { !validCategory || it.startsWith(categoryPrefix!!) }.toTypedArray()

        var total: Int = 0
        val leaderboard: JsonArray = JsonArray()
        try {
            dataSource.connection.use { conn ->
                val registeredIds: Array = conn.createArrayOf("text", registeredAdvancementIds)
                try {
                    conn
                        .prepareStatement(
                            "SELECT COUNT(DISTINCT p.minecraft_uuid)::int" +
                                " FROM player_advancements p" +
                                " WHERE p.season = ? AND p.completed = true" +
                                " AND p.advancement_id = ANY (?)" +
                                " AND EXISTS (SELECT 1 FROM players eligible_player" +
                                " WHERE eligible_player.minecraft_uuid = p.minecraft_uuid" +
                                " AND eligible_player.is_discord_member = true" +
                                " AND (? OR eligible_player.awards_excluded = false)" +
                                " AND eligible_player.last_mc_login_at >=" +
                                " EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000)"
                        )
                        .use { stmt ->
                            stmt.setString(1, season)
                            stmt.setArray(2, registeredIds)
                            stmt.setBoolean(3, showHidden)
                            stmt.executeQuery().use { rs ->
                                if (rs.next()) total = rs.getInt(1)
                            }
                        }

                    conn
                        .prepareStatement(
                            "SELECT" +
                                " p.minecraft_uuid," +
                                " u.minecraft_username," +
                                " u.nickname," +
                                " u.awards_excluded AS hidden," +
                                " COUNT(*) FILTER (WHERE p.completed = true)::int AS completed" +
                                " FROM player_advancements p" +
                                " LEFT JOIN players u ON u.minecraft_uuid = p.minecraft_uuid" +
                                " WHERE p.season = ?" +
                                " AND p.advancement_id = ANY (?)" +
                                " AND EXISTS (SELECT 1 FROM players eligible_player" +
                                " WHERE eligible_player.minecraft_uuid = p.minecraft_uuid" +
                                " AND eligible_player.is_discord_member = true" +
                                " AND (? OR eligible_player.awards_excluded = false)" +
                                " AND eligible_player.last_mc_login_at >=" +
                                " EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000)" +
                                " GROUP BY p.minecraft_uuid, u.minecraft_username, u.nickname, u.awards_excluded" +
                                " HAVING COUNT(*) FILTER (WHERE p.completed = true) > 0" +
                                " ORDER BY completed DESC, p.minecraft_uuid" +
                                " LIMIT ? OFFSET ?"
                        )
                        .use { stmt ->
                            stmt.setString(1, season)
                            stmt.setArray(2, registeredIds)
                            stmt.setBoolean(3, showHidden)
                            stmt.setInt(4, limit)
                            stmt.setInt(5, offset)
                            stmt.executeQuery().use { rs ->
                                var rank: Int = offset
                                while (rs.next()) {
                                    rank++
                                    val entry: JsonObject = JsonObject()
                                    entry.addProperty("rank", rank)
                                    LeaderboardIdentity.addTo(entry, rs)
                                    entry.addProperty("completed", rs.getInt("completed"))
                                    leaderboard.add(entry)
                                }
                            }
                        }
                } finally {
                    registeredIds.free()
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to load advancement leaderboard", e)
        }

        val advTotal = if (validCategory) registry.getTotalForCategory(category) else registry.getTotal()

        val response: JsonObject = JsonObject()
        response.add("leaderboard", leaderboard)
        response.addProperty("total", total)
        response.addProperty("totalAdvancements", advTotal)
        if (validCategory) {
            response.addProperty("category", category)
        }
        response.addProperty("offset", offset)
        response.addProperty("limit", limit)
        return response
    }
}
