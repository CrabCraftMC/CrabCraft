package crabcraft.net.crabUtilities.velocity.awards;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * Persists award scores and derived tables to Postgres.
 *
 * Pipeline, called per stats update:
 * <ol>
 *   <li>{@link #upsertPlayerScores(Connection, String, String, String, Map)}
 *       writes one row per award for the given (player, server).</li>
 *   <li>{@link #recomputePlayerAggregate(Connection, String, String)} sums
 *       the player's per-server rows into the {@code __aggregate__}
 *       sentinel row used by the cross-server leaderboards.</li>
 *   <li>{@link #recomputeMedals(Connection, String, String)} and
 *       {@link #recomputeCrownScores(Connection, String, String)} refresh
 *       the global medal / crown-score state for that slice.</li>
 * </ol>
 * The two recompute steps touch every row in the slice, but the slice is
 * at most (~active players) &times; 202 awards which is trivial for
 * Postgres. They're idempotent so concurrent runs converge.
 */
public final class AwardDbWriter {

    public static final String AGGREGATE_SERVER_ID = "__aggregate__";

    private static final String UPSERT_SCORE = """
        INSERT INTO player_award_scores
            (minecraft_uuid, season, server_id, award_id, score, medal, computed_at)
        VALUES (?, ?, ?, ?, ?, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
        ON CONFLICT (minecraft_uuid, season, server_id, award_id) DO UPDATE SET
            score = EXCLUDED.score,
            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
        """;

    private static final String RECOMPUTE_AGGREGATE_FOR_PLAYER = """
        INSERT INTO player_award_scores
            (minecraft_uuid, season, server_id, award_id, score, medal, computed_at)
        SELECT
            minecraft_uuid, season, ?::text, award_id, SUM(score), 0,
            EXTRACT(EPOCH FROM NOW())::INTEGER
        FROM player_award_scores
        WHERE minecraft_uuid = ?
          AND season = ?
          AND server_id <> ?
        GROUP BY minecraft_uuid, season, award_id
        ON CONFLICT (minecraft_uuid, season, server_id, award_id) DO UPDATE SET
            score = EXCLUDED.score,
            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
        """;

    /**
     * Recompute medals for every (award_id, player) pair within a
     * (season, server_id) slice. Ties share the same medal (RANK() gives
     * two golds and then no silver, matching upstream MinecraftStats).
     */
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
            WHERE season = ? AND server_id = ?
        ) ranked
        WHERE pas.id = ranked.id
        """;

    /**
     * Weighted crown score: gold * 4 + silver * 2 + bronze * 1, matching
     * the explainer tooltip on the web /leaderboard page.
     */
    private static final String RECOMPUTE_CROWN = """
        INSERT INTO player_crown_scores
            (minecraft_uuid, season, server_id, gold, silver, bronze, crown_score, computed_at)
        SELECT
            minecraft_uuid,
            season,
            server_id,
            COUNT(*) FILTER (WHERE medal = 1)::int AS gold,
            COUNT(*) FILTER (WHERE medal = 2)::int AS silver,
            COUNT(*) FILTER (WHERE medal = 3)::int AS bronze,
            (COUNT(*) FILTER (WHERE medal = 1) * 4
             + COUNT(*) FILTER (WHERE medal = 2) * 2
             + COUNT(*) FILTER (WHERE medal = 3))::int AS crown_score,
            EXTRACT(EPOCH FROM NOW())::INTEGER
        FROM player_award_scores
        WHERE season = ? AND server_id = ?
        GROUP BY minecraft_uuid, season, server_id
        ON CONFLICT (minecraft_uuid, season, server_id) DO UPDATE SET
            gold = EXCLUDED.gold,
            silver = EXCLUDED.silver,
            bronze = EXCLUDED.bronze,
            crown_score = EXCLUDED.crown_score,
            computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
        """;

    private final HikariDataSource dataSource;
    private final Logger logger;

    public AwardDbWriter(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    /**
     * Run the full write + recompute pipeline for one player whose stats
     * just arrived from one backend server.
     */
    public void writeForPlayerOnServer(String uuid, String season,
                                        String serverId, Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertPlayerScores(conn, uuid, season, serverId, scores);
                recomputePlayerAggregate(conn, uuid, season);
                recomputeMedals(conn, season, serverId);
                recomputeMedals(conn, season, AGGREGATE_SERVER_ID);
                recomputeCrownScores(conn, season, serverId);
                recomputeCrownScores(conn, season, AGGREGATE_SERVER_ID);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to write award scores for uuid={} server={}", uuid, serverId, e);
        }
    }

    public void upsertPlayerScores(Connection conn, String uuid, String season,
                                    String serverId, Map<String, Double> scores) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UPSERT_SCORE)) {
            for (Map.Entry<String, Double> entry : scores.entrySet()) {
                stmt.setString(1, uuid);
                stmt.setString(2, season);
                stmt.setString(3, serverId);
                stmt.setString(4, entry.getKey());
                stmt.setDouble(5, entry.getValue() == null ? 0d : entry.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    public void recomputePlayerAggregate(Connection conn, String uuid, String season) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(RECOMPUTE_AGGREGATE_FOR_PLAYER)) {
            stmt.setString(1, AGGREGATE_SERVER_ID);
            stmt.setString(2, uuid);
            stmt.setString(3, season);
            stmt.setString(4, AGGREGATE_SERVER_ID);
            stmt.executeUpdate();
        }
    }

    public void recomputeMedals(Connection conn, String season, String serverId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(RECOMPUTE_MEDALS)) {
            stmt.setString(1, season);
            stmt.setString(2, serverId);
            stmt.executeUpdate();
        }
    }

    public void recomputeCrownScores(Connection conn, String season, String serverId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(RECOMPUTE_CROWN)) {
            stmt.setString(1, season);
            stmt.setString(2, serverId);
            stmt.executeUpdate();
        }
    }
}
