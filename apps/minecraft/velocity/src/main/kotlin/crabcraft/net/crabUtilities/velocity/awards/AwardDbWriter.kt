package crabcraft.net.crabUtilities.velocity.awards

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.Connection
import java.sql.SQLException

/** Persists award scores and medal rankings to Postgres. */
class AwardDbWriter(private val dataSource: HikariDataSource, private val logger: Logger) {
    /**
     * Writes one player's scores without refreshing season-wide medals.
     * Callers that process many players should batch/debounce
     * {@link #recomputeMedals(String)} instead of running it once per player.
     */
    fun writeScoresForPlayer(uuid: String?, season: String?, scores: Map<String, Double?>?) {
        if (scores == null || scores.isEmpty()) return
        try {
            dataSource.getConnection().use { conn ->
                conn.setAutoCommit(false)
                try {
                    upsertPlayerScores(conn, uuid, season, scores)
                    conn.commit()
                } catch (e: SQLException) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.setAutoCommit(true)
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to write award scores for uuid={}", uuid, e)
        }
    }

    fun recomputeMedals(season: String?) {
        try {
            dataSource.getConnection().use { conn ->
                conn.setAutoCommit(false)
                try {
                    recomputeMedalsInTransaction(conn, season)
                    conn.commit()
                } catch (e: SQLException) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.setAutoCommit(true)
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to recompute award medals for season={}", season, e)
        }
    }

    /** Reassign every season after a previously inactive player logs in. */
    fun recomputeAllMedals() {
        try {
            dataSource.getConnection().use { conn ->
                conn.setAutoCommit(false)
                try {
                    val seasons = ArrayList<String>()
                    conn.prepareStatement(
                        "SELECT DISTINCT season FROM player_award_scores").use { stmt ->
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) seasons.add(rs.getString("season"))
                        }
                    }
                    for (season in seasons) {
                        recomputeMedalsInTransaction(conn, season)
                    }
                    conn.commit()
                } catch (e: SQLException) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.setAutoCommit(true)
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to recompute award medals for all seasons", e)
        }
    }

    private fun upsertPlayerScores(conn: Connection, uuid: String?, season: String?, scores: Map<String, Double?>) {
        conn.prepareStatement(UPSERT_SCORE).use { stmt ->
            for (entry in scores.entries) {
                stmt.setString(1, uuid)
                stmt.setString(2, season)
                stmt.setString(3, entry.key)
                stmt.setDouble(4, entry.value ?: 0.0)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private fun recomputeMedalsInTransaction(conn: Connection, season: String?) {
        conn.prepareStatement(RESET_MEDALS).use { stmt ->
            stmt.setString(1, season)
            stmt.executeUpdate()
        }
        conn.prepareStatement(RECOMPUTE_MEDALS).use { stmt ->
            stmt.setString(1, season)
            stmt.executeUpdate()
        }
    }

    companion object {
        private val UPSERT_SCORE = """
            INSERT INTO player_award_scores
                (minecraft_uuid, season, award_id, score, medal, computed_at)
            VALUES (?, ?, ?, ?, 0, EXTRACT(EPOCH FROM NOW())::INTEGER)
            ON CONFLICT (minecraft_uuid, season, award_id) DO UPDATE SET
                score = EXCLUDED.score,
                computed_at = EXTRACT(EPOCH FROM NOW())::INTEGER
            """.trimIndent() + "\n"

        private val RESET_MEDALS = """
            UPDATE player_award_scores
            SET medal = 0
            WHERE season = ?
            """.trimIndent() + "\n"

        private val RECOMPUTE_MEDALS = """
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
            """.trimIndent() + "\n"
    }
}
