package crabcraft.net.crabUtilities.velocity.awards;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/** Persists award scores and medal rankings to Postgres. */
public final class AwardDbWriter {

    private static final String UPSERT_SCORE = """
        INSERT INTO player_award_scores
            (minecraft_uuid, season, award_id, score, medal, computed_at)
        VALUES (?, ?, ?, ?, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
        ON CONFLICT (minecraft_uuid, season, award_id) DO UPDATE SET
            score = EXCLUDED.score,
            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
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
        if (scores == null || scores.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertPlayerScores(conn, uuid, season, scores);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to write award scores for uuid={}", uuid, e);
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
