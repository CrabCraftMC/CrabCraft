package crabcraft.net.crabUtilities.velocity.advancements

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger

import java.sql.Connection
import java.sql.SQLException
import java.util.HashMap

class AdvancementQueryService(private val dataSource: HikariDataSource, private val logger: Logger, private val registry: AdvancementRegistry) {

    private fun resolveSeason(seasonParam: String?): String? {
        if (seasonParam != null && !seasonParam.isEmpty()) return seasonParam
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT id FROM seasons WHERE is_current = true LIMIT 1").use { stmt ->
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

    fun getPlayerAdvancements(uuid: String?, seasonParam: String?): JsonObject? {
        val season = resolveSeason(seasonParam)
        if (season == null) return null

        try {
            dataSource.getConnection().use { conn ->
                var username: String? = null
                conn.prepareStatement(
                    "SELECT minecraft_username FROM players WHERE minecraft_uuid = ? LIMIT 1").use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) username = rs.getString("minecraft_username")
                    }
                }

                val completionStatus = HashMap<String?, Boolean>()
                val timestamps = HashMap<String?, Int>()
                conn.prepareStatement("""
                        SELECT advancement_id, completed, completed_at
                        FROM player_advancements
                        WHERE minecraft_uuid = ? AND season = ?
                          AND advancement_id NOT LIKE 'minecraft:recipes/%'
                        """.trimIndent() + "\n").use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.setString(2, season)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            var advId = rs.getString("advancement_id")
                            completionStatus.put(advId, rs.getBoolean("completed"))
                            var completedAt = rs.getInt("completed_at")
                            if (!rs.wasNull()) {
                                timestamps.put(advId, completedAt)
                            }
                        }
                    }
                }

                var advancements = JsonObject()
                var completed = 0

                for (regEntry in registry.getAll().entries) {
                    var advId = regEntry.key
                    var meta = regEntry.value

                    var done = (completionStatus[advId] == true)
                    if (done) completed++

                    var entry = JsonObject()
                    entry.addProperty("name", meta.get("name").getAsString())
                    entry.addProperty("description", meta.get("description").getAsString())
                    entry.addProperty("category", meta.get("category").getAsString())
                    entry.addProperty("completed", done)
                    val ts = timestamps.get(advId)
                    if (ts != null) {
                        entry.addProperty("completed_at", ts)
                    } else {
                        entry.add("completed_at", null)
                    }
                    advancements.add(advId, entry)
                }

                var response = JsonObject()
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

    fun getAdvancementLeaderboard(seasonParam: String?, limitParam: Int, offsetParam: Int, category: String?): JsonObject? {
        var limit = limitParam
        var offset = offsetParam
        val season = resolveSeason(seasonParam)
        if (season == null) return null
        if (limit <= 0 || limit > 100) limit = 100
        if (offset < 0) offset = 0

        var validCategory = registry.isValidCategory(category)
        val categoryPrefix = if (validCategory) "minecraft:" + category + "/" else null
        val registeredAdvancementIds = registry.getAll().keys
            .filter { id -> !validCategory || id.startsWith(categoryPrefix!!) }
            .toTypedArray()

        var total = 0
        var leaderboard = JsonArray()
        try {
            dataSource.getConnection().use { conn ->
                var registeredIds = conn.createArrayOf("text", registeredAdvancementIds)
                try {
                    conn.prepareStatement(
                        "SELECT COUNT(DISTINCT p.minecraft_uuid)::int"
                        + " FROM player_advancements p"
                        + " WHERE p.season = ? AND p.completed = true"
                        + " AND p.advancement_id = ANY (?)"
                        + " AND EXISTS (SELECT 1 FROM players eligible_player"
                        + " WHERE eligible_player.minecraft_uuid = p.minecraft_uuid"
                        + " AND eligible_player.is_discord_member = true"
                        + " AND eligible_player.last_mc_login_at >="
                        + " EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000)").use { stmt ->
                        stmt.setString(1, season)
                        stmt.setArray(2, registeredIds)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) total = rs.getInt(1)
                        }
                    }

                    conn.prepareStatement(
                        "SELECT"
                        + " p.minecraft_uuid,"
                        + " u.minecraft_username,"
                        + " u.nickname,"
                        + " COUNT(*) FILTER (WHERE p.completed = true)::int AS completed"
                        + " FROM player_advancements p"
                        + " LEFT JOIN players u ON u.minecraft_uuid = p.minecraft_uuid"
                        + " WHERE p.season = ?"
                        + " AND p.advancement_id = ANY (?)"
                        + " AND EXISTS (SELECT 1 FROM players eligible_player"
                        + " WHERE eligible_player.minecraft_uuid = p.minecraft_uuid"
                        + " AND eligible_player.is_discord_member = true"
                        + " AND eligible_player.last_mc_login_at >="
                        + " EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000)"
                        + " GROUP BY p.minecraft_uuid, u.minecraft_username, u.nickname"
                        + " HAVING COUNT(*) FILTER (WHERE p.completed = true) > 0"
                        + " ORDER BY completed DESC, p.minecraft_uuid"
                        + " LIMIT ? OFFSET ?").use { stmt ->
                        stmt.setString(1, season)
                        stmt.setArray(2, registeredIds)
                        stmt.setInt(3, limit)
                        stmt.setInt(4, offset)
                        stmt.executeQuery().use { rs ->
                            var rank = offset
                            while (rs.next()) {
                                rank++
                                var entry = JsonObject()
                                entry.addProperty("rank", rank)
                                entry.addProperty("uuid", rs.getString("minecraft_uuid"))
                                entry.addProperty("username", rs.getString("minecraft_username"))
                                entry.addProperty("nickname", rs.getString("nickname"))
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

        var response = JsonObject()
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
