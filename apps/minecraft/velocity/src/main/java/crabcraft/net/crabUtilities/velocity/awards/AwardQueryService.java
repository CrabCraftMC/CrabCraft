package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public final class AwardQueryService {

    private final HikariDataSource dataSource;
    private final Logger logger;

    public AwardQueryService(HikariDataSource dataSource, Logger logger) {
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

    public JsonObject getAllAwards(String seasonParam) {
        String season = resolveSeason(seasonParam);
        if (season == null) return null;

        JsonArray awardsArray = new JsonArray();
        try (Connection conn = dataSource.getConnection()) {

            Map<String, JsonObject> leaderMap = new HashMap<>();
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT DISTINCT ON (p.award_id)
                        p.award_id,
                        p.minecraft_uuid AS best_uuid,
                        u.minecraft_username AS best_username,
                        p.score AS best_score
                    FROM player_award_scores p
                    LEFT JOIN players u ON u.minecraft_uuid = p.minecraft_uuid
                    WHERE p.season = ?
                      AND p.score > 0
                    ORDER BY p.award_id, p.score DESC
                    """)) {
                stmt.setString(1, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        JsonObject leader = new JsonObject();
                        leader.addProperty("uuid", rs.getString("best_uuid"));
                        leader.addProperty("username", rs.getString("best_username"));
                        leader.addProperty("score", rs.getDouble("best_score"));
                        leaderMap.put(rs.getString("award_id"), leader);
                    }
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT id, title, description, unit, bucket, icon
                    FROM awards
                    WHERE enabled = true
                    ORDER BY bucket, sort_order, title
                    """);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    JsonObject award = new JsonObject();
                    String id = rs.getString("id");
                    award.addProperty("id", id);
                    award.addProperty("title", rs.getString("title"));
                    award.addProperty("description", rs.getString("description"));
                    award.addProperty("unit", rs.getString("unit"));
                    award.addProperty("bucket", rs.getString("bucket"));
                    award.addProperty("icon", rs.getString("icon"));
                    award.add("leader", leaderMap.getOrDefault(id, null));
                    awardsArray.add(award);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load awards overview", e);
        }

        JsonObject response = new JsonObject();
        response.add("awards", awardsArray);
        return response;
    }

    private JsonObject getAwardDefinitionById(Connection conn, String awardId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, title, description, unit, bucket, icon " +
                "FROM awards WHERE id = ? AND enabled = true LIMIT 1")) {
            stmt.setString(1, awardId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                JsonObject award = new JsonObject();
                award.addProperty("id", rs.getString("id"));
                award.addProperty("title", rs.getString("title"));
                award.addProperty("description", rs.getString("description"));
                award.addProperty("unit", rs.getString("unit"));
                award.addProperty("bucket", rs.getString("bucket"));
                award.addProperty("icon", rs.getString("icon"));
                return award;
            }
        }
    }

    public JsonObject getAwardLeaderboard(String awardId, String seasonParam,
                                           int limit, int offset) {
        String season = resolveSeason(seasonParam);
        if (season == null) return null;
        if (limit <= 0 || limit > 100) limit = 100;
        if (offset < 0) offset = 0;

        try (Connection conn = dataSource.getConnection()) {
            JsonObject awardDef = getAwardDefinitionById(conn, awardId);
            if (awardDef == null) {
                JsonObject notFound = new JsonObject();
                notFound.addProperty("notFound", true);
                return notFound;
            }

            int total = 0;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*)::int FROM player_award_scores " +
                    "WHERE award_id = ? AND season = ? AND score > 0")) {
                stmt.setString(1, awardId);
                stmt.setString(2, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            JsonArray leaderboard = new JsonArray();
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT p.minecraft_uuid, u.minecraft_username, p.score, p.medal
                    FROM player_award_scores p
                    LEFT JOIN players u ON u.minecraft_uuid = p.minecraft_uuid
                    WHERE p.award_id = ? AND p.season = ?
                    ORDER BY p.score DESC
                    LIMIT ? OFFSET ?
                    """)) {
                stmt.setString(1, awardId);
                stmt.setString(2, season);
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
                        entry.addProperty("score", rs.getDouble("score"));
                        entry.addProperty("medal", rs.getInt("medal"));
                        leaderboard.add(entry);
                    }
                }
            }

            JsonObject response = new JsonObject();
            response.add("award", awardDef);
            response.add("leaderboard", leaderboard);
            response.addProperty("total", total);
            response.addProperty("offset", offset);
            response.addProperty("limit", limit);
            return response;
        } catch (SQLException e) {
            logger.error("Failed to load leaderboard for award={}", awardId, e);
            return null;
        }
    }

    public JsonObject getCrownLeaderboard(String seasonParam, int limit, int offset) {
        String season = resolveSeason(seasonParam);
        if (season == null) return null;
        if (limit <= 0 || limit > 100) limit = 100;
        if (offset < 0) offset = 0;

        int total = 0;
        JsonArray leaderboard = new JsonArray();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT COUNT(*)::int FROM (
                        SELECT minecraft_uuid
                        FROM player_award_scores
                        WHERE season = ? AND medal > 0
                        GROUP BY minecraft_uuid
                    ) ranked
                    """)) {
                stmt.setString(1, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement("""
                     SELECT
                         c.minecraft_uuid,
                         u.minecraft_username,
                         c.gold,
                         c.silver,
                         c.bronze,
                         c.crown_score
                     FROM (
                         SELECT
                             minecraft_uuid,
                             COUNT(*) FILTER (WHERE medal = 1)::int AS gold,
                             COUNT(*) FILTER (WHERE medal = 2)::int AS silver,
                             COUNT(*) FILTER (WHERE medal = 3)::int AS bronze,
                             (COUNT(*) FILTER (WHERE medal = 1) * 5
                              + COUNT(*) FILTER (WHERE medal = 2) * 3
                              + COUNT(*) FILTER (WHERE medal = 3))::int AS crown_score
                         FROM player_award_scores
                         WHERE season = ?
                         GROUP BY minecraft_uuid
                     ) c
                     LEFT JOIN players u ON u.minecraft_uuid = c.minecraft_uuid
                     WHERE c.crown_score > 0
                     ORDER BY c.crown_score DESC, c.gold DESC, c.silver DESC
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
                        entry.addProperty("gold", rs.getInt("gold"));
                        entry.addProperty("silver", rs.getInt("silver"));
                        entry.addProperty("bronze", rs.getInt("bronze"));
                        entry.addProperty("crown_score", rs.getInt("crown_score"));
                        leaderboard.add(entry);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load crown leaderboard", e);
        }

        JsonObject response = new JsonObject();
        response.add("leaderboard", leaderboard);
        response.addProperty("total", total);
        response.addProperty("offset", offset);
        response.addProperty("limit", limit);
        return response;
    }

    public JsonObject getPlayerAwards(String uuid, String seasonParam) {
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

            JsonObject scores = new JsonObject();
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT award_id, score, rank FROM (
                        SELECT
                            award_id,
                            minecraft_uuid,
                            score,
                            RANK() OVER (PARTITION BY award_id ORDER BY score DESC) AS rank
                        FROM player_award_scores
                        WHERE season = ? AND score > 0
                    ) ranked
                    WHERE minecraft_uuid = ?
                    """)) {
                stmt.setString(1, season);
                stmt.setString(2, uuid);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("rank", rs.getInt("rank"));
                        entry.addProperty("score", rs.getDouble("score"));
                        scores.add(rs.getString("award_id"), entry);
                    }
                }
            }

            if (scores.size() == 0) {
                JsonObject notFound = new JsonObject();
                notFound.addProperty("notFound", true);
                return notFound;
            }

            JsonObject crown = null;
            try (PreparedStatement stmt = conn.prepareStatement("""
                    WITH crown AS (
                        SELECT
                            minecraft_uuid,
                            COUNT(*) FILTER (WHERE medal = 1)::int AS gold,
                            COUNT(*) FILTER (WHERE medal = 2)::int AS silver,
                            COUNT(*) FILTER (WHERE medal = 3)::int AS bronze,
                            (COUNT(*) FILTER (WHERE medal = 1) * 5
                             + COUNT(*) FILTER (WHERE medal = 2) * 3
                             + COUNT(*) FILTER (WHERE medal = 3))::int AS crown_score
                        FROM player_award_scores
                        WHERE season = ?
                        GROUP BY minecraft_uuid
                    ),
                    ranked AS (
                        SELECT
                            minecraft_uuid, gold, silver, bronze, crown_score,
                            RANK() OVER (ORDER BY crown_score DESC, gold DESC, silver DESC) AS rank
                        FROM crown
                        WHERE crown_score > 0
                    )
                    SELECT * FROM ranked WHERE minecraft_uuid = ? LIMIT 1
                    """)) {
                stmt.setString(1, season);
                stmt.setString(2, uuid);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        crown = new JsonObject();
                        crown.addProperty("rank", rs.getInt("rank"));
                        crown.addProperty("gold", rs.getInt("gold"));
                        crown.addProperty("silver", rs.getInt("silver"));
                        crown.addProperty("bronze", rs.getInt("bronze"));
                        crown.addProperty("crown_score", rs.getInt("crown_score"));
                    }
                }
            }

            JsonObject response = new JsonObject();
            response.addProperty("uuid", uuid);
            response.addProperty("username", username);
            response.add("crown", crown);
            response.add("scores", scores);
            return response;
        } catch (SQLException e) {
            logger.error("Failed to load player awards for uuid={}", uuid, e);
            return null;
        }
    }
}
