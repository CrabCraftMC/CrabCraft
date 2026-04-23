package crabcraft.net.crabUtilities.velocity.awards;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * Persists award scores to Postgres.
 *
 * Pipeline, called per stats update:
 * <ol>
 *   <li>{@link #upsertPlayerScores} writes one row per award for the player.</li>
 *   <li>{@link #recomputeMedals} refreshes the top-3 medal state for that season.</li>
 * </ol>
 */
public final class AwardDbWriter {

    private static final String UPSERT_SCORE = """
        INSERT INTO player_award_scores
            (minecraft_uuid, season, award_id, score, medal, computed_at)
        VALUES (?, ?, ?, ?, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
        ON CONFLICT (minecraft_uuid, season, award_id) DO UPDATE SET
            score = EXCLUDED.score,
            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
        """;

    private static final String RECOMPUTE_MEDALS = """
        UPDATE player_award_scores pas
        SET medal = CASE
                WHEN pas.score <= 0 THEN 0
                WHEN ranked.rnk = 1 THEN 1
                WHEN ranked.rnk = 2 THEN 2
                WHEN ranked.rnk = 3 THEN 3
                ELSE 0
            END
        FROM (
            SELECT id, RANK() OVER (PARTITION BY award_id ORDER BY score DESC) AS rnk
            FROM player_award_scores
            WHERE season = ?
        ) ranked
        WHERE pas.id = ranked.id
        """;

    private final HikariDataSource dataSource;
    private final Logger logger;

    public AwardDbWriter(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    /**
     * Run the full write + recompute pipeline for one player.
     */
    public void writeForPlayer(String uuid, String season, Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertPlayerScores(conn, uuid, season, scores);
                recomputeMedals(conn, season);
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

    private void recomputeMedals(Connection conn, String season) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(RECOMPUTE_MEDALS)) {
            stmt.setString(1, season);
            stmt.executeUpdate();
        }
    }
}
