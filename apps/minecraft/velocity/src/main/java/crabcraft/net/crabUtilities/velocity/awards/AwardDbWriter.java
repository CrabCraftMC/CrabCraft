package crabcraft.net.crabUtilities.velocity.awards;

import com.zaxxer.hikari.HikariDataSource;
import com.google.gson.JsonParser;
import crabcraft.net.crabUtilities.awards.EatingAwardSnapshot;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Persists award scores and medal rankings to Postgres. */
public final class AwardDbWriter {

    private static final String UPSERT_SCORE = """
        INSERT INTO player_award_scores
            (minecraft_uuid, season, award_id, score, medal, computed_at)
        VALUES (?, ?, ?, ?, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
        ON CONFLICT (minecraft_uuid, season, award_id) DO UPDATE SET
            score = EXCLUDED.score,
            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
        WHERE player_award_scores.eating_progress IS NULL
        """;

    private static final String RESET_MEDALS = """
        UPDATE player_award_scores
        SET medal = 0
        WHERE season = ?
        """;

    private static final String RECOMPUTE_MEDALS = """
        WITH ranked AS (
            SELECT
                scores.id,
                RANK() OVER (
                    PARTITION BY scores.award_id ORDER BY scores.score DESC
                ) AS rnk
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
                    AND eligible_player.awards_excluded = false
                    AND eligible_player.last_mc_login_at >=
                        EXTRACT(EPOCH FROM NOW())::INTEGER - 2592000
              )
        )
        UPDATE player_award_scores scores
        SET medal = ranked.rnk::int
        FROM ranked
        WHERE scores.id = ranked.id AND ranked.rnk <= 3
        """;

    private final HikariDataSource dataSource;
    private final Logger logger;

    public AwardDbWriter(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    /**
     * Writes one player's scores without refreshing season-wide medals.
     * Callers that process many players should batch/debounce
     * {@link #recomputeMedals(String)} instead of running it once per player.
     */
    public void writeScoresForPlayer(String uuid, String season, Map<String, Double> scores) {
        writeScoresForPlayer(uuid, season, scores, Map.of());
    }

    public void writeScoresForPlayer(String uuid, String season, Map<String, Double> scores,
                                     Map<String, EatingAwardSnapshot> eating) {
        Map<String, Double> ordinaryScores = new TreeMap<>(scores == null ? Map.of() : scores);
        eating.keySet().forEach(ordinaryScores::remove);
        if (ordinaryScores.isEmpty() && eating.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertPlayerScores(conn, uuid, season, ordinaryScores);
                for (var entry : new TreeMap<>(eating).entrySet()) {
                    writeEatingScore(conn, uuid, season, entry.getKey(), entry.getValue());
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to write award scores for uuid={}", uuid, e);
        }
    }

    private void writeEatingScore(Connection conn, String uuid, String season, String award,
                                  EatingAwardSnapshot incoming) throws SQLException {
        boolean inserted;
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO player_award_scores (minecraft_uuid, season, award_id, score, medal, computed_at)
                VALUES (?, ?, ?, 0, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
                ON CONFLICT (minecraft_uuid, season, award_id) DO NOTHING
                """)) {
            stmt.setString(1, uuid);
            stmt.setString(2, season);
            stmt.setString(3, award);
            inserted = stmt.executeUpdate() == 1;
        }
        // Lock the score and checkpoint together: two workers can receive the same player at once.
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT score, eating_progress FROM player_award_scores
                WHERE minecraft_uuid = ? AND season = ? AND award_id = ? FOR UPDATE
                """)) {
            stmt.setString(1, uuid);
            stmt.setString(2, season);
            stmt.setString(3, award);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) throw new SQLException("Eating-award row disappeared");
                String saved = rs.getString("eating_progress");
                var checkpoint = saved == null ? null : JsonParser.parseString(saved).getAsJsonObject();
                EatingAwardSnapshot previous = checkpoint == null ? null : EatingAwardSnapshot.fromJson(checkpoint);
                // REAL is a leaderboard projection, not an exact accumulator. Retain every meal in JSONB.
                BigDecimal accumulatedScore = checkpoint != null && checkpoint.has("accumulatedScore")
                        ? checkpoint.get("accumulatedScore").getAsBigDecimal()
                        : (inserted ? null : rs.getBigDecimal("score"));
                BigDecimal score = EatingAwardScore.advance(accumulatedScore, previous, incoming);
                if (score == null) return;
                var nextCheckpoint = incoming.toJson();
                nextCheckpoint.addProperty("accumulatedScore", score);
                try (PreparedStatement update = conn.prepareStatement("""
                        UPDATE player_award_scores SET score = ?, eating_progress = ?::jsonb,
                            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
                        WHERE minecraft_uuid = ? AND season = ? AND award_id = ?
                        """)) {
                    update.setBigDecimal(1, score);
                    update.setString(2, nextCheckpoint.toString());
                    update.setString(3, uuid);
                    update.setString(4, season);
                    update.setString(5, award);
                    update.executeUpdate();
                }
            }
        }
    }

    public void recomputeMedals(String season) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                recomputeMedalsInTransaction(conn, season);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to recompute award medals for season={}", season, e);
        }
    }

    /** Reassign every season after a previously inactive player logs in. */
    public void recomputeAllMedals() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<String> seasons = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT DISTINCT season FROM player_award_scores");
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) seasons.add(rs.getString("season"));
                }
                for (String season : seasons) {
                    recomputeMedalsInTransaction(conn, season);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to recompute award medals for all seasons", e);
        }
    }

    private void upsertPlayerScores(Connection conn, String uuid, String season,
                                     Map<String, Double> scores) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UPSERT_SCORE)) {
            for (Map.Entry<String, Double> entry : scores.entrySet()) {
                stmt.setString(1, uuid);
                stmt.setString(2, season);
                stmt.setString(3, entry.getKey());
                stmt.setDouble(4, entry.getValue() == null ? 0d : entry.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void recomputeMedalsInTransaction(Connection conn, String season) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(RESET_MEDALS)) {
            stmt.setString(1, season);
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement(RECOMPUTE_MEDALS)) {
            stmt.setString(1, season);
            stmt.executeUpdate();
        }
    }
}
