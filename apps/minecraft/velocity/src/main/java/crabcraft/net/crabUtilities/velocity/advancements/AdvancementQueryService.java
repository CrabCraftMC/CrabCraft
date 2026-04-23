package crabcraft.net.crabUtilities.velocity.advancements;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class AdvancementQueryService {

    private final HikariDataSource dataSource;
    private final Logger logger;

    public AdvancementQueryService(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    private String resolveSeason(String seasonParam) {
        if (seasonParam != null && !seasonParam.isEmpty()) return seasonParam;
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

    public JsonObject getPlayerAdvancements(String uuid, String seasonParam) {
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

            JsonObject advancements = new JsonObject();
            int completed = 0;
            int total = 0;
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT advancement_id, completed, completed_at
                    FROM player_advancements
                    WHERE minecraft_uuid = ? AND season = ?
                      AND advancement_id NOT LIKE 'minecraft:recipes/%'
                    ORDER BY advancement_id
                    """)) {
                stmt.setString(1, uuid);
                stmt.setString(2, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        total++;
                        boolean done = rs.getBoolean("completed");
                        if (done) completed++;

                        JsonObject entry = new JsonObject();
                        entry.addProperty("completed", done);
                        int completedAt = rs.getInt("completed_at");
                        if (rs.wasNull()) {
                            entry.add("completed_at", null);
                        } else {
                            entry.addProperty("completed_at", completedAt);
                        }
                        advancements.add(rs.getString("advancement_id"), entry);
                    }
                }
            }

            if (total == 0) {
                JsonObject notFound = new JsonObject();
                notFound.addProperty("notFound", true);
                return notFound;
            }

            JsonObject response = new JsonObject();
            response.addProperty("uuid", uuid);
            response.addProperty("username", username);
            response.addProperty("completed", completed);
            response.addProperty("total", total);
            response.add("advancements", advancements);
            return response;
        } catch (SQLException e) {
            logger.error("Failed to load advancements for uuid={}", uuid, e);
            return null;
        }
    }

    public JsonObject getAdvancementLeaderboard(String seasonParam, int limit, int offset) {
        String season = resolveSeason(seasonParam);
        if (season == null) return null;
        if (limit <= 0 || limit > 100) limit = 100;
        if (offset < 0) offset = 0;

        int total = 0;
        JsonArray leaderboard = new JsonArray();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT COUNT(DISTINCT minecraft_uuid)::int
                    FROM player_advancements
                    WHERE season = ? AND completed = true
                      AND advancement_id NOT LIKE 'minecraft:recipes/%'
                    """)) {
                stmt.setString(1, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT
                        p.minecraft_uuid,
                        u.minecraft_username,
                        COUNT(*) FILTER (WHERE p.completed = true)::int AS completed
                    FROM player_advancements p
                    LEFT JOIN players u ON u.minecraft_uuid = p.minecraft_uuid
                    WHERE p.season = ?
                      AND p.advancement_id NOT LIKE 'minecraft:recipes/%'
                    GROUP BY p.minecraft_uuid, u.minecraft_username
                    HAVING COUNT(*) FILTER (WHERE p.completed = true) > 0
                    ORDER BY completed DESC, p.minecraft_uuid
                    LIMIT ? OFFSET ?
                    """)) {
                stmt.setString(1, season);
                stmt.setInt(2, limit);
                stmt.setInt(3, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    int rank = offset;
                    while (rs.next()) {
                        rank++;
                        JsonObject entry = new JsonObject();
                        entry.addProperty("rank", rank);
                        entry.addProperty("uuid", rs.getString("minecraft_uuid"));
                        entry.addProperty("username", rs.getString("minecraft_username"));
                        entry.addProperty("completed", rs.getInt("completed"));
                        leaderboard.add(entry);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load advancement leaderboard", e);
        }

        JsonObject response = new JsonObject();
        response.add("leaderboard", leaderboard);
        response.addProperty("total", total);
        response.addProperty("offset", offset);
        response.addProperty("limit", limit);
        return response;
    }
}
