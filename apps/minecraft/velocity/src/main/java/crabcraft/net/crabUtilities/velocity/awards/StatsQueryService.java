package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class StatsQueryService {

    private final HikariDataSource dataSource;
    private final Logger logger;

    public StatsQueryService(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    public String getCurrentSeason() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT id FROM seasons WHERE is_current = true LIMIT 1");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getString("id") : null;
        } catch (SQLException e) {
            logger.error("Failed to resolve current season", e);
            return null;
        }
    }

    public String resolveSeason(String seasonParam) {
        if (seasonParam != null && !seasonParam.isEmpty()) return seasonParam;
        return getCurrentSeason();
    }

    public JsonObject getPlayerStats(String uuid, String seasonParam) {
        String season = resolveSeason(seasonParam);
        if (season == null) return null;

        try (Connection conn = dataSource.getConnection()) {
            String username = null;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT minecraft_username FROM players WHERE minecraft_uuid = ? LIMIT 1")) {
                stmt.setString(1, uuid);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) username = rs.getString("minecraft_username");
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM player_season_stats WHERE minecraft_uuid = ? AND season = ? LIMIT 1")) {
                stmt.setString(1, uuid);
                stmt.setString(2, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        JsonObject notFound = new JsonObject();
                        notFound.addProperty("notFound", true);
                        return notFound;
                    }

                    JsonObject stats = new JsonObject();

                    // Integer columns
                    stats.addProperty("play_time_seconds", rs.getInt("play_time_seconds"));
                    stats.addProperty("mob_kills", rs.getInt("mob_kills"));
                    stats.addProperty("player_kills", rs.getInt("player_kills"));
                    stats.addProperty("deaths", rs.getInt("deaths"));
                    stats.addProperty("damage_dealt", rs.getInt("damage_dealt"));
                    stats.addProperty("damage_taken", rs.getInt("damage_taken"));
                    stats.addProperty("total_blocks_mined", rs.getInt("total_blocks_mined"));
                    stats.addProperty("total_blocks_placed", rs.getInt("total_blocks_placed"));
                    stats.addProperty("total_items_crafted", rs.getInt("total_items_crafted"));
                    stats.addProperty("total_items_broken", rs.getInt("total_items_broken"));
                    stats.addProperty("jumps", rs.getInt("jumps"));
                    stats.addProperty("animals_bred", rs.getInt("animals_bred"));
                    stats.addProperty("fish_caught", rs.getInt("fish_caught"));
                    stats.addProperty("villagers_traded", rs.getInt("villagers_traded"));
                    stats.addProperty("enchantments", rs.getInt("enchantments"));
                    stats.addProperty("times_slept", rs.getInt("times_slept"));

                    // Distance columns (real / double)
                    stats.addProperty("walk_distance_m", rs.getDouble("walk_distance_m"));
                    stats.addProperty("sprint_distance_m", rs.getDouble("sprint_distance_m"));
                    stats.addProperty("swim_distance_m", rs.getDouble("swim_distance_m"));
                    stats.addProperty("fly_distance_m", rs.getDouble("fly_distance_m"));
                    stats.addProperty("boat_distance_m", rs.getDouble("boat_distance_m"));
                    stats.addProperty("elytra_distance_m", rs.getDouble("elytra_distance_m"));
                    stats.addProperty("horse_distance_m", rs.getDouble("horse_distance_m"));
                    stats.addProperty("climb_distance_m", rs.getDouble("climb_distance_m"));
                    stats.addProperty("fall_distance_m", rs.getDouble("fall_distance_m"));
                    stats.addProperty("total_distance_m", rs.getDouble("total_distance_m"));

                    // Nullable text columns (serialize to JSON null when absent)
                    stats.addProperty("top_block_mined", rs.getString("top_block_mined"));
                    stats.addProperty("top_mob_killed", rs.getString("top_mob_killed"));
                    stats.addProperty("top_item_crafted", rs.getString("top_item_crafted"));
                    stats.addProperty("top_item_used", rs.getString("top_item_used"));
                    stats.addProperty("top_death_cause", rs.getString("top_death_cause"));

                    // computed_at
                    int computedAt = rs.getInt("computed_at");
                    if (rs.wasNull()) {
                        stats.add("computed_at", null);
                    } else {
                        stats.addProperty("computed_at", computedAt);
                    }

                    JsonObject response = new JsonObject();
                    response.addProperty("uuid", uuid);
                    response.addProperty("username", username);
                    response.addProperty("season", season);
                    response.add("stats", stats);
                    return response;
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load player stats for uuid={}", uuid, e);
            return null;
        }
    }

    public JsonArray getPlayerSeasons(String uuid) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT s.id, s.name FROM player_season_stats pss "
                 + "JOIN seasons s ON pss.season = s.id "
                 + "WHERE pss.minecraft_uuid = ? "
                 + "ORDER BY s.created_at DESC")) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                JsonArray seasons = new JsonArray();
                while (rs.next()) {
                    JsonObject season = new JsonObject();
                    season.addProperty("id", rs.getString("id"));
                    season.addProperty("name", rs.getString("name"));
                    seasons.add(season);
                }
                return seasons;
            }
        } catch (SQLException e) {
            logger.error("Failed to load player seasons for uuid={}", uuid, e);
            return null;
        }
    }
}
