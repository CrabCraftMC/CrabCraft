package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger

import java.sql.Connection
import java.sql.SQLException

class StatsQueryService(private val dataSource: HikariDataSource, private val logger: Logger) {

    fun getCurrentSeason(): String? {
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

    fun resolveSeason(seasonParam: String?): String? {
        if (seasonParam != null && !seasonParam.isEmpty()) return seasonParam
        return getCurrentSeason()
    }

    fun getPlayerStats(uuid: String?, seasonParam: String?): JsonObject? {
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

                conn.prepareStatement(
                    "SELECT * FROM player_season_stats WHERE minecraft_uuid = ? AND season = ? LIMIT 1").use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.setString(2, season)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            var notFound = JsonObject()
                            notFound.addProperty("notFound", true)
                            return notFound
                        }

                        var stats = JsonObject()

                        // Integer columns
                        stats.addProperty("play_time_seconds", rs.getInt("play_time_seconds"))
                        stats.addProperty("mob_kills", rs.getInt("mob_kills"))
                        stats.addProperty("player_kills", rs.getInt("player_kills"))
                        stats.addProperty("deaths", rs.getInt("deaths"))
                        stats.addProperty("damage_dealt", rs.getInt("damage_dealt"))
                        stats.addProperty("damage_taken", rs.getInt("damage_taken"))
                        stats.addProperty("total_blocks_mined", rs.getInt("total_blocks_mined"))
                        stats.addProperty("total_blocks_placed", rs.getInt("total_blocks_placed"))
                        stats.addProperty("total_items_crafted", rs.getInt("total_items_crafted"))
                        stats.addProperty("total_items_broken", rs.getInt("total_items_broken"))
                        stats.addProperty("jumps", rs.getInt("jumps"))
                        stats.addProperty("animals_bred", rs.getInt("animals_bred"))
                        stats.addProperty("fish_caught", rs.getInt("fish_caught"))
                        stats.addProperty("villagers_traded", rs.getInt("villagers_traded"))
                        stats.addProperty("enchantments", rs.getInt("enchantments"))
                        stats.addProperty("times_slept", rs.getInt("times_slept"))

                        // Distance columns (real / double)
                        stats.addProperty("walk_distance_m", rs.getDouble("walk_distance_m"))
                        stats.addProperty("sprint_distance_m", rs.getDouble("sprint_distance_m"))
                        stats.addProperty("swim_distance_m", rs.getDouble("swim_distance_m"))
                        stats.addProperty("fly_distance_m", rs.getDouble("fly_distance_m"))
                        stats.addProperty("boat_distance_m", rs.getDouble("boat_distance_m"))
                        stats.addProperty("elytra_distance_m", rs.getDouble("elytra_distance_m"))
                        stats.addProperty("horse_distance_m", rs.getDouble("horse_distance_m"))
                        stats.addProperty("climb_distance_m", rs.getDouble("climb_distance_m"))
                        stats.addProperty("fall_distance_m", rs.getDouble("fall_distance_m"))
                        stats.addProperty("total_distance_m", rs.getDouble("total_distance_m"))

                        // Nullable text columns (serialize to JSON null when absent)
                        stats.addProperty("top_block_mined", rs.getString("top_block_mined"))
                        stats.addProperty("top_mob_killed", rs.getString("top_mob_killed"))
                        stats.addProperty("top_item_crafted", rs.getString("top_item_crafted"))
                        stats.addProperty("top_item_used", rs.getString("top_item_used"))
                        stats.addProperty("top_death_cause", rs.getString("top_death_cause"))

                        // computed_at
                        var computedAt = rs.getInt("computed_at")
                        if (rs.wasNull()) {
                            stats.add("computed_at", null)
                        } else {
                            stats.addProperty("computed_at", computedAt)
                        }

                        var response = JsonObject()
                        response.addProperty("uuid", uuid)
                        response.addProperty("username", username)
                        response.addProperty("season", season)
                        response.add("stats", stats)
                        return response
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to load player stats for uuid={}", uuid, e)
            return null
        }
    }

    fun getPlayerSeasons(uuid: String?): JsonArray? {
        try {
            dataSource.getConnection().use { conn ->
                conn.prepareStatement(
                    "SELECT s.id, s.name FROM player_season_stats pss "
                    + "JOIN seasons s ON pss.season = s.id "
                    + "WHERE pss.minecraft_uuid = ? "
                    + "ORDER BY s.created_at DESC").use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeQuery().use { rs ->
                        var seasons = JsonArray()
                        while (rs.next()) {
                            var season = JsonObject()
                            season.addProperty("id", rs.getString("id"))
                            season.addProperty("name", rs.getString("name"))
                            seasons.add(season)
                        }
                        return seasons
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to load player seasons for uuid={}", uuid, e)
            return null
        }
    }
}
