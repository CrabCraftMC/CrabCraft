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
                    SELECT DISTINCT ON (scores.award_id)
                        scores.award_id,
                        scores.minecraft_uuid AS best_uuid,
                        u.minecraft_username AS best_username,
                        u.nickname AS best_nickname,
                        scores.score AS best_score
                    FROM player_award_scores scores
                    LEFT JOIN players u ON u.minecraft_uuid = scores.minecraft_uuid
                    WHERE scores.season = ?
                      AND scores.score > 0
                      AND NOT EXISTS (
                          SELECT 1 FROM player_alts alt
                          WHERE alt.minecraft_uuid = scores.minecraft_uuid
                      )
                      AND EXISTS (
                          SELECT 1 FROM players eligible_player
                          WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
                            AND eligible_player.is_discord_member = true
                            AND eligible_player.last_mc_login_at >=
                                EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
                      )
                    ORDER BY scores.award_id, scores.score DESC, scores.minecraft_uuid
                    """)) {
                stmt.setString(1, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        JsonObject leader = new JsonObject();
                        leader.addProperty("uuid", rs.getString("best_uuid"));
                        leader.addProperty("username", rs.getString("best_username"));
                        leader.addProperty("nickname", rs.getString("best_nickname"));
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
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT COUNT(*)::int
                    FROM player_award_scores scores
                    WHERE scores.award_id = ? AND scores.season = ? AND scores.score > 0
                      AND NOT EXISTS (
                          SELECT 1 FROM player_alts alt
                          WHERE alt.minecraft_uuid = scores.minecraft_uuid
                      )
                      AND EXISTS (
                          SELECT 1 FROM players eligible_player
                          WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
                            AND eligible_player.is_discord_member = true
                            AND eligible_player.last_mc_login_at >=
                                EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
                      )
                    """)) {
                stmt.setString(1, awardId);
                stmt.setString(2, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            JsonArray leaderboard = new JsonArray();
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT
                        ranked.minecraft_uuid,
                        u.minecraft_username,
                        u.nickname,
                        ranked.score,
                        ranked.rnk,
                        CASE WHEN ranked.rnk <= 3 THEN ranked.rnk::int ELSE 0 END AS medal
                    FROM (
                        SELECT
                            scores.minecraft_uuid,
                            scores.score,
                            RANK() OVER (ORDER BY scores.score DESC) AS rnk
                        FROM player_award_scores scores
                        WHERE scores.award_id = ? AND scores.season = ? AND scores.score > 0
                          AND NOT EXISTS (
                              SELECT 1 FROM player_alts alt
                              WHERE alt.minecraft_uuid = scores.minecraft_uuid
                          )
                          AND EXISTS (
                              SELECT 1 FROM players eligible_player
                              WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
                                AND eligible_player.is_discord_member = true
                                AND eligible_player.last_mc_login_at >=
                                    EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
                          )
                    ) ranked
                    LEFT JOIN players u ON u.minecraft_uuid = ranked.minecraft_uuid
                    ORDER BY ranked.score DESC, ranked.minecraft_uuid
                    LIMIT ? OFFSET ?
                    """)) {
                stmt.setString(1, awardId);
                stmt.setString(2, season);
                stmt.setInt(3, limit);
                stmt.setInt(4, offset);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("rank", rs.getInt("rnk"));
                        entry.addProperty("uuid", rs.getString("minecraft_uuid"));
                        entry.addProperty("username", rs.getString("minecraft_username"));
                        entry.addProperty("nickname", rs.getString("nickname"));
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
                    WITH ranked_scores AS (
                        SELECT
                            scores.minecraft_uuid,
                            RANK() OVER (
                                PARTITION BY scores.award_id ORDER BY scores.score DESC
                            ) AS medal_rank
                        FROM player_award_scores scores
                        WHERE scores.season = ? AND scores.score > 0
                          AND NOT EXISTS (
                              SELECT 1 FROM player_alts alt
                              WHERE alt.minecraft_uuid = scores.minecraft_uuid
                          )
                          AND EXISTS (
                              SELECT 1 FROM players eligible_player
                              WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
                                AND eligible_player.is_discord_member = true
                                AND eligible_player.last_mc_login_at >=
                                    EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
                          )
                    )
                    SELECT COUNT(DISTINCT minecraft_uuid)::int
                    FROM ranked_scores
                    WHERE medal_rank <= 3
                    """)) {
                stmt.setString(1, season);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            try (PreparedStatement stmt = conn.prepareStatement("""
                     WITH ranked_scores AS (
                         SELECT
                             scores.minecraft_uuid,
                             RANK() OVER (
                                 PARTITION BY scores.award_id ORDER BY scores.score DESC
                             ) AS medal_rank
                         FROM player_award_scores scores
                         WHERE scores.season = ? AND scores.score > 0
                           AND NOT EXISTS (
                               SELECT 1 FROM player_alts alt
                               WHERE alt.minecraft_uuid = scores.minecraft_uuid
                           )
                           AND EXISTS (
                               SELECT 1 FROM players eligible_player
                               WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
                                 AND eligible_player.is_discord_member = true
                                 AND eligible_player.last_mc_login_at >=
                                     EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
                           )
                     ),
                     crowns AS (
                         SELECT
                             minecraft_uuid,
                             COUNT(*) FILTER (WHERE medal_rank = 1)::int AS gold,
                             COUNT(*) FILTER (WHERE medal_rank = 2)::int AS silver,
                             COUNT(*) FILTER (WHERE medal_rank = 3)::int AS bronze,
                             (COUNT(*) FILTER (WHERE medal_rank = 1) * 5
                              + COUNT(*) FILTER (WHERE medal_rank = 2) * 3
                              + COUNT(*) FILTER (WHERE medal_rank = 3))::int AS crown_score
                         FROM ranked_scores
                         WHERE medal_rank <= 3
                         GROUP BY minecraft_uuid
                     )
                     SELECT
                         crowns.minecraft_uuid,
                         u.minecraft_username,
                         u.nickname,
                         crowns.gold,
                         crowns.silver,
                         crowns.bronze,
                         crowns.crown_score
                     FROM crowns
                     LEFT JOIN players u ON u.minecraft_uuid = crowns.minecraft_uuid
                     ORDER BY crowns.crown_score DESC, crowns.gold DESC, crowns.silver DESC
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
                        entry.addProperty("nickname", rs.getString("nickname"));
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
                            scores.award_id,
                            scores.minecraft_uuid,
                            scores.score,
                            RANK() OVER (
                                PARTITION BY scores.award_id ORDER BY scores.score DESC
                            ) AS rank
                        FROM player_award_scores scores
                        WHERE scores.season = ? AND scores.score > 0
                          AND NOT EXISTS (
                              SELECT 1 FROM player_alts alt
                              WHERE alt.minecraft_uuid = scores.minecraft_uuid
                          )
                          AND EXISTS (
                              SELECT 1 FROM players eligible_player
                              WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
                                AND eligible_player.is_discord_member = true
                                AND eligible_player.last_mc_login_at >=
                                    EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
                          )
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
                    WITH ranked_scores AS (
                        SELECT
                            scores.minecraft_uuid,
                            RANK() OVER (
                                PARTITION BY scores.award_id ORDER BY scores.score DESC
                            ) AS medal_rank
                        FROM player_award_scores scores
                        WHERE scores.season = ? AND scores.score > 0
                          AND NOT EXISTS (
                              SELECT 1 FROM player_alts alt
                              WHERE alt.minecraft_uuid = scores.minecraft_uuid
                          )
                          AND EXISTS (
                              SELECT 1 FROM players eligible_player
                              WHERE eligible_player.minecraft_uuid = scores.minecraft_uuid
                                AND eligible_player.is_discord_member = true
                                AND eligible_player.last_mc_login_at >=
                                    EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
                          )
                    ),
                    crown AS (
                        SELECT
                            minecraft_uuid,
                            COUNT(*) FILTER (WHERE medal_rank = 1)::int AS gold,
                            COUNT(*) FILTER (WHERE medal_rank = 2)::int AS silver,
                            COUNT(*) FILTER (WHERE medal_rank = 3)::int AS bronze,
                            (COUNT(*) FILTER (WHERE medal_rank = 1) * 5
                             + COUNT(*) FILTER (WHERE medal_rank = 2) * 3
                             + COUNT(*) FILTER (WHERE medal_rank = 3))::int AS crown_score
                        FROM ranked_scores
                        WHERE medal_rank <= 3
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
