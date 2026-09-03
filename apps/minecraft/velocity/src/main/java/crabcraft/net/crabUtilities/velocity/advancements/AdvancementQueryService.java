package crabcraft.net.crabUtilities.velocity.advancements;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public final class AdvancementQueryService {

    private final HikariDataSource dataSource;
    private final Logger logger;
    private final AdvancementRegistry registry;

    public AdvancementQueryService(HikariDataSource dataSource, Logger logger,
                                    AdvancementRegistry registry) {
        this.dataSource = dataSource;
        this.logger = logger;
        this.registry = registry;
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

            Map<String, Boolean> completionStatus = new HashMap<>();
            Map<String, Integer> timestamps = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT advancement_id, completed, completed_at
                    FROM player_advancements
                    WHERE minecraft_uuid = ? AND season = ?
                      AND advancement_id NOT LIKE 'minecraft:recipes/%'
                    """)) {
                stmt.setString(1, uuid);
                stmt.setString(2, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String advId = rs.getString("advancement_id");
                        completionStatus.put(advId, rs.getBoolean("completed"));
                        int completedAt = rs.getInt("completed_at");
                        if (!rs.wasNull()) {
                            timestamps.put(advId, completedAt);
                        }
                    }
                }
            }

            JsonObject advancements = new JsonObject();
            int completed = 0;

            for (Map.Entry<String, JsonObject> regEntry : registry.getAll().entrySet()) {
                String advId = regEntry.getKey();
                JsonObject meta = regEntry.getValue();

                boolean done = Boolean.TRUE.equals(completionStatus.get(advId));
                if (done) completed++;

                JsonObject entry = new JsonObject();
                entry.addProperty("name", meta.get("name").getAsString());
                entry.addProperty("description", meta.get("description").getAsString());
                entry.addProperty("category", meta.get("category").getAsString());
                entry.addProperty("completed", done);
                Integer ts = timestamps.get(advId);
                if (ts != null) {
                    entry.addProperty("completed_at", ts);
                } else {
                    entry.add("completed_at", null);
                }
                advancements.add(advId, entry);
            }

            JsonObject response = new JsonObject();
            response.addProperty("uuid", uuid);
            response.addProperty("username", username);
            response.addProperty("completed", completed);
            response.addProperty("total", registry.getTotal());
            response.add("advancements", advancements);
            return response;
        } catch (SQLException e) {
            logger.error("Failed to load advancements for uuid={}", uuid, e);
            return null;
        }
    }

    public JsonObject getAdvancementLeaderboard(String seasonParam, int limit, int offset,
                                                String category) {
        String season = resolveSeason(seasonParam);
        if (season == null) return null;
        if (limit <= 0 || limit > 100) limit = 100;
        if (offset < 0) offset = 0;

        boolean validCategory = registry.isValidCategory(category);
        String categoryPrefix = validCategory ? "minecraft:" + category + "/" : null;
        String[] registeredAdvancementIds = registry.getAll().keySet().stream()
                .filter(id -> !validCategory || id.startsWith(categoryPrefix))
                .toArray(String[]::new);

        int total = 0;
        JsonArray leaderboard = new JsonArray();
        try (Connection conn = dataSource.getConnection()) {
            Array registeredIds = conn.createArrayOf("text", registeredAdvancementIds);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(DISTINCT p.minecraft_uuid)::int"
                        + " FROM player_advancements p"
                        + " WHERE p.season = ? AND p.completed = true"
                        + " AND p.advancement_id = ANY (?)"
                        + " AND EXISTS (SELECT 1 FROM players eligible_player"
                        + " WHERE eligible_player.minecraft_uuid = p.minecraft_uuid"
                        + " AND eligible_player.is_discord_member = true"
                        + " AND eligible_player.last_mc_login_at >="
                        + " EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000)")) {
                    stmt.setString(1, season);
                    stmt.setArray(2, registeredIds);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) total = rs.getInt(1);
                    }
                }

                try (PreparedStatement stmt = conn.prepareStatement(
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
                        + " LIMIT ? OFFSET ?")) {
                    stmt.setString(1, season);
                    stmt.setArray(2, registeredIds);
                    stmt.setInt(3, limit);
                    stmt.setInt(4, offset);
                    try (ResultSet rs = stmt.executeQuery()) {
                        int rank = offset;
                        while (rs.next()) {
                            rank++;
                            JsonObject entry = new JsonObject();
                            entry.addProperty("rank", rank);
                            entry.addProperty("uuid", rs.getString("minecraft_uuid"));
                            entry.addProperty("username", rs.getString("minecraft_username"));
                            entry.addProperty("nickname", rs.getString("nickname"));
                            entry.addProperty("completed", rs.getInt("completed"));
                            leaderboard.add(entry);
                        }
                    }
                }
            } finally {
                registeredIds.free();
            }
        } catch (SQLException e) {
            logger.error("Failed to load advancement leaderboard", e);
        }

        int advTotal = validCategory
                ? registry.getTotalForCategory(category)
                : registry.getTotal();

        JsonObject response = new JsonObject();
        response.add("leaderboard", leaderboard);
        response.addProperty("total", total);
        response.addProperty("totalAdvancements", advTotal);
        if (validCategory) {
            response.addProperty("category", category);
        }
        response.addProperty("offset", offset);
        response.addProperty("limit", limit);
        return response;
    }
}
