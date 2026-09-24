package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariDataSource
import crabcraft.net.crabUtilities.velocity.api.LeaderboardIdentity
import java.sql.Connection
import java.sql.SQLException
import java.util.HashMap
import org.slf4j.Logger

class AwardQueryService(private val dataSource: HikariDataSource, private val logger: Logger) {

    fun getCurrentSeason(): String? {
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT id FROM seasons WHERE is_current = true LIMIT 1").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        return if (rs.next()) rs.getString("id") else null
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to resolve current season", e)
            return null
        }
    }

    fun resolveSeason(seasonParam: String?): String? {
        if (seasonParam != null && !seasonParam.isEmpty()) return seasonParam
        return getCurrentSeason()
    }

    fun getAllAwards(seasonParam: String?): JsonObject? {
        return getAllAwards(seasonParam, false)
    }

    fun getAllAwards(seasonParam: String?, showHidden: Boolean): JsonObject? {
        val season = resolveSeason(seasonParam)
        if (season == null) return null

        val awardsArray: JsonArray = JsonArray()
        try {
            dataSource.connection.use { conn ->
                val leaderMap: MutableMap<String, JsonObject> = HashMap()
                conn.prepareStatement(AWARD_LEADERS_SQL).use { stmt ->
                    stmt.setString(1, season)
                    stmt.setBoolean(2, showHidden)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val leader: JsonObject = JsonObject()
                            LeaderboardIdentity.addTo(leader, rs)
                            leader.addProperty("score", rs.getDouble("best_score"))
                            leaderMap.put(rs.getString("award_id"), leader)
                        }
                    }
                }

                conn
                    .prepareStatement(
                        ("""
                        SELECT id, title, description, unit, bucket, icon
                        FROM awards
                        WHERE enabled = true
                        ORDER BY bucket, sort_order, title
                        """
                            .trimIndent() + "\n")
                    )
                    .use { stmt ->
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                var award: JsonObject = JsonObject()
                                var id: String = rs.getString("id")
                                award.addProperty("id", id)
                                award.addProperty("title", rs.getString("title"))
                                award.addProperty("description", rs.getString("description"))
                                award.addProperty("unit", rs.getString("unit"))
                                award.addProperty("bucket", rs.getString("bucket"))
                                award.addProperty("icon", rs.getString("icon"))
                                award.add("leader", leaderMap[id])
                                awardsArray.add(award)
                            }
                        }
                    }
            }
        } catch (e: SQLException) {
            logger.error("Failed to load awards overview", e)
        }

        val response: JsonObject = JsonObject()
        response.add("awards", awardsArray)
        return response
    }

    private fun getAwardDefinitionById(conn: Connection, awardId: String): JsonObject? {
        conn
            .prepareStatement(
                "SELECT id, title, description, unit, bucket, icon " +
                    "FROM awards WHERE id = ? AND enabled = true LIMIT 1"
            )
            .use { stmt ->
                stmt.setString(1, awardId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    var award: JsonObject = JsonObject()
                    award.addProperty("id", rs.getString("id"))
                    award.addProperty("title", rs.getString("title"))
                    award.addProperty("description", rs.getString("description"))
                    award.addProperty("unit", rs.getString("unit"))
                    award.addProperty("bucket", rs.getString("bucket"))
                    award.addProperty("icon", rs.getString("icon"))
                    return award
                }
            }
    }

    fun getAwardLeaderboard(awardId: String, seasonParam: String?, limit: Int, offset: Int): JsonObject? {
        return getAwardLeaderboard(awardId, seasonParam, limit, offset, false)
    }

    fun getAwardLeaderboard(
        awardId: String,
        seasonParam: String?,
        limit: Int,
        offset: Int,
        showHidden: Boolean,
    ): JsonObject? {
        var limit = limit
        var offset = offset
        val season = resolveSeason(seasonParam)
        if (season == null) return null
        if (limit <= 0 || limit > 100) limit = 100
        if (offset < 0) offset = 0

        try {
            dataSource.connection.use { conn ->
                val awardDef = getAwardDefinitionById(conn, awardId)
                if (awardDef == null) {
                    val notFound: JsonObject = JsonObject()
                    notFound.addProperty("notFound", true)
                    return notFound
                }

                var total: Int = 0
                conn.prepareStatement(AWARD_COUNT_SQL).use { stmt ->
                    stmt.setString(1, awardId)
                    stmt.setString(2, season)
                    stmt.setBoolean(3, showHidden)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) total = rs.getInt(1)
                    }
                }

                val leaderboard: JsonArray = JsonArray()
                conn.prepareStatement(AWARD_LEADERBOARD_SQL).use { stmt ->
                    stmt.setString(1, awardId)
                    stmt.setString(2, season)
                    stmt.setBoolean(3, showHidden)
                    stmt.setInt(4, limit)
                    stmt.setInt(5, offset)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val entry: JsonObject = JsonObject()
                            entry.addProperty("rank", rs.getInt("rnk"))
                            LeaderboardIdentity.addTo(entry, rs)
                            entry.addProperty("score", rs.getDouble("score"))
                            entry.addProperty("medal", rs.getInt("medal"))
                            leaderboard.add(entry)
                        }
                    }
                }

                val response: JsonObject = JsonObject()
                response.add("award", awardDef)
                response.add("leaderboard", leaderboard)
                response.addProperty("total", total)
                response.addProperty("offset", offset)
                response.addProperty("limit", limit)
                return response
            }
        } catch (e: SQLException) {
            logger.error("Failed to load leaderboard for award={}", awardId, e)
            return null
        }
    }

    fun getCrownLeaderboard(seasonParam: String?, limit: Int, offset: Int): JsonObject? {
        return getCrownLeaderboard(seasonParam, limit, offset, false)
    }

    fun getCrownLeaderboard(seasonParam: String?, limit: Int, offset: Int, showHidden: Boolean): JsonObject? {
        var limit = limit
        var offset = offset
        val season = resolveSeason(seasonParam)
        if (season == null) return null
        if (limit <= 0 || limit > 100) limit = 100
        if (offset < 0) offset = 0

        var total: Int = 0
        val leaderboard: JsonArray = JsonArray()
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(CROWN_COUNT_SQL).use { stmt ->
                    stmt.setString(1, season)
                    stmt.setBoolean(2, showHidden)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) total = rs.getInt(1)
                    }
                }

                conn.prepareStatement(CROWN_LEADERBOARD_SQL).use { stmt ->
                    stmt.setString(1, season)
                    stmt.setBoolean(2, showHidden)
                    stmt.setInt(3, limit)
                    stmt.setInt(4, offset)
                    stmt.executeQuery().use { rs ->
                        var rank: Int = offset
                        while (rs.next()) {
                            rank++
                            val entry: JsonObject = JsonObject()
                            entry.addProperty("rank", rank)
                            LeaderboardIdentity.addTo(entry, rs)
                            entry.addProperty("gold", rs.getInt("gold"))
                            entry.addProperty("silver", rs.getInt("silver"))
                            entry.addProperty("bronze", rs.getInt("bronze"))
                            entry.addProperty("crown_score", rs.getInt("crown_score"))
                            leaderboard.add(entry)
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to load crown leaderboard", e)
        }

        val response: JsonObject = JsonObject()
        response.add("leaderboard", leaderboard)
        response.addProperty("total", total)
        response.addProperty("offset", offset)
        response.addProperty("limit", limit)
        return response
    }

    fun getPlayerAwards(uuid: String, seasonParam: String?): JsonObject? {
        val season = resolveSeason(seasonParam)
        if (season == null) return null

        try {
            dataSource.connection.use { conn ->
                var username: String? = null
                conn.prepareStatement("SELECT minecraft_username FROM players WHERE minecraft_uuid = ? LIMIT 1").use {
                    stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) username = rs.getString("minecraft_username")
                    }
                }

                val scores: JsonObject = JsonObject()
                conn.prepareStatement(PLAYER_AWARDS_SQL).use { stmt ->
                    stmt.setString(1, season)
                    stmt.setString(2, uuid)
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val entry: JsonObject = JsonObject()
                            entry.addProperty("rank", rs.getInt("rank"))
                            entry.addProperty("score", rs.getDouble("score"))
                            scores.add(rs.getString("award_id"), entry)
                        }
                    }
                }

                if (scores.size() == 0) {
                    val notFound: JsonObject = JsonObject()
                    notFound.addProperty("notFound", true)
                    return notFound
                }

                var crown: JsonObject? = null
                conn.prepareStatement(PLAYER_CROWN_SQL).use { stmt ->
                    stmt.setString(1, season)
                    stmt.setString(2, uuid)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            crown =
                                JsonObject().apply {
                                    addProperty("rank", rs.getInt("rank"))
                                    addProperty("gold", rs.getInt("gold"))
                                    addProperty("silver", rs.getInt("silver"))
                                    addProperty("bronze", rs.getInt("bronze"))
                                    addProperty("crown_score", rs.getInt("crown_score"))
                                }
                        }
                    }
                }

                val response: JsonObject = JsonObject()
                response.addProperty("uuid", uuid)
                response.addProperty("username", username)
                response.add("crown", crown)
                response.add("scores", scores)
                return response
            }
        } catch (e: SQLException) {
            logger.error("Failed to load player awards for uuid={}", uuid, e)
            return null
        }
    }

    companion object {
        private val AWARD_LEADERS_SQL: String =
            ("""
            SELECT DISTINCT ON (scores.award_id)
                scores.award_id,
                scores.minecraft_uuid,
                u.minecraft_username,
                u.nickname,
                u.awards_excluded AS hidden,
                scores.score AS best_score
            FROM player_award_scores scores
            LEFT JOIN players u ON u.minecraft_uuid = scores.minecraft_uuid
            WHERE scores.season = ?
              AND scores.score > 0
            """
                .trimIndent() + "\n") +
                AwardEligibility.WITH_VISIBILITY +
                ("""
                ORDER BY scores.award_id, scores.score DESC, scores.minecraft_uuid
                """
                    .trimIndent() + "\n")

        private val AWARD_COUNT_SQL: String =
            ("""
            SELECT COUNT(*)::int
            FROM player_award_scores scores
            WHERE scores.award_id = ? AND scores.season = ? AND scores.score > 0
            """
                .trimIndent() + "\n") + AwardEligibility.WITH_VISIBILITY

        private val AWARD_LEADERBOARD_SQL: String =
            ("""
            SELECT
                ranked.minecraft_uuid,
                u.minecraft_username,
                u.nickname,
                u.awards_excluded AS hidden,
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
            """
                .trimIndent() + "\n") +
                AwardEligibility.WITH_VISIBILITY +
                ("""
                ) ranked
                LEFT JOIN players u ON u.minecraft_uuid = ranked.minecraft_uuid
                ORDER BY ranked.score DESC, ranked.minecraft_uuid
                LIMIT ? OFFSET ?
                """
                    .trimIndent() + "\n")

        private val CROWN_COUNT_SQL: String =
            ("""
            WITH ranked_scores AS (
                SELECT
                    scores.minecraft_uuid,
                    RANK() OVER (
                        PARTITION BY scores.award_id ORDER BY scores.score DESC
                    ) AS medal_rank
                FROM player_award_scores scores
                WHERE scores.season = ? AND scores.score > 0
            """
                .trimIndent() + "\n") +
                AwardEligibility.WITH_VISIBILITY +
                ("""
                )
                SELECT COUNT(DISTINCT minecraft_uuid)::int
                FROM ranked_scores
                WHERE medal_rank <= 3
                """
                    .trimIndent() + "\n")

        private val CROWN_LEADERBOARD_SQL: String =
            ("""
            WITH ranked_scores AS (
                SELECT
                    scores.minecraft_uuid,
                    RANK() OVER (
                        PARTITION BY scores.award_id ORDER BY scores.score DESC
                    ) AS medal_rank
                FROM player_award_scores scores
                WHERE scores.season = ? AND scores.score > 0
            """
                .trimIndent() + "\n") +
                AwardEligibility.WITH_VISIBILITY +
                ("""
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
                    u.awards_excluded AS hidden,
                    crowns.gold,
                    crowns.silver,
                    crowns.bronze,
                    crowns.crown_score
                FROM crowns
                LEFT JOIN players u ON u.minecraft_uuid = crowns.minecraft_uuid
                ORDER BY crowns.crown_score DESC, crowns.gold DESC, crowns.silver DESC, crowns.minecraft_uuid
                LIMIT ? OFFSET ?
                """
                    .trimIndent() + "\n")

        private val PLAYER_AWARDS_SQL: String =
            ("""
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
            """
                .trimIndent() + "\n") +
                AwardEligibility.PUBLIC_SCORES +
                ("""
                ) ranked
                WHERE minecraft_uuid = ?
                """
                    .trimIndent() + "\n")

        private val PLAYER_CROWN_SQL: String =
            ("""
            WITH ranked_scores AS (
                SELECT
                    scores.minecraft_uuid,
                    RANK() OVER (
                        PARTITION BY scores.award_id ORDER BY scores.score DESC
                    ) AS medal_rank
                FROM player_award_scores scores
                WHERE scores.season = ? AND scores.score > 0
            """
                .trimIndent() + "\n") +
                AwardEligibility.PUBLIC_SCORES +
                ("""
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
                """
                    .trimIndent() + "\n")
    }
}
