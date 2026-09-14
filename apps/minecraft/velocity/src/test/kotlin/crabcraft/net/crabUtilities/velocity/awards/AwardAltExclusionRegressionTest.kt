package crabcraft.net.crabUtilities.velocity.awards

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

object AwardAltExclusionRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val dataSource = CapturingDataSource()
        val logger = proxy(Logger::class.java) { _, method, _ -> defaultValue(method.returnType) }
        val queries = AwardQueryService(dataSource, logger)

        val overview = queries.getAllAwards("7")
        val awardLeaderboard = queries.getAwardLeaderboard("test-award", "7", 10, 0)
        queries.getCrownLeaderboard("7", 10, 0)
        queries.getPlayerAwards("primary-uuid", "7")

        val visibleLeader = overview!!.getAsJsonArray("awards").get(0).asJsonObject.getAsJsonObject("leader")
        check(!visibleLeader.get("hidden").asBoolean && visibleLeader.get("uuid").asString == dataSource.visibleUuid,
            "default awards overview lost its visible leader")

        val entries = awardLeaderboard!!.getAsJsonArray("leaderboard")
        check(entries.size() == 3, "expected the tie-ranking fixture")
        check(entries.get(0).asJsonObject.get("rank").asInt == 1,
            "first tied player did not receive rank 1")
        check(entries.get(1).asJsonObject.get("rank").asInt == 1,
            "second tied player did not receive rank 1")
        check(entries.get(2).asJsonObject.get("rank").asInt == 3,
            "rank after a two-way tie should skip to 3")

        val rankingQueries = dataSource.sql.filter { sql -> sql.contains("player_award_scores") }
        check(rankingQueries.size == 7, "expected every public award query to be exercised")
        check(rankingQueries.all { sql -> sql.contains("player_alts") },
            "a public award query does not exclude alt accounts")
        check(rankingQueries.all { sql -> sql.contains("is_discord_member") },
            "a public award query does not exclude departed Discord members")
        check(rankingQueries.all { sql -> sql.contains("eligible_player.awards_excluded = false") },
            "a public award query can expose a moderator-excluded player")
        check(rankingQueries.all { sql -> sql.contains("last_mc_login_at") },
            "a public award query does not exclude inactive players")
        check(rankingQueries.all { sql -> sql.contains("2592000") },
            "a public award query does not use the 30-day window")
        check(rankingQueries.count { sql -> sql.contains("rank() over") } == 5,
            "medal-bearing award queries do not derive ranks after filtering alts")

        check(dataSource.visibility.all { value -> !value },
            "a leaderboard includes hidden players by default")
        for (response in listOf(
            queries.getAllAwards("7", true)!!,
            queries.getAwardLeaderboard("test-award", "7", 10, 0, true)!!,
            queries.getCrownLeaderboard("7", 10, 0, true)!!
        )) {
            val hidden = if (response.has("awards")) {
                response.getAsJsonArray("awards").get(0).asJsonObject.getAsJsonObject("leader")
            } else {
                response.getAsJsonArray("leaderboard").get(0).asJsonObject
            }
            check(hidden.get("hidden").asBoolean, "hidden entry lost its marker")
            check(hidden.get("uuid").isJsonNull && hidden.get("nickname").isJsonNull,
                "hidden entry exposes a UUID or nickname")
            check(hidden.get("username").asString == "Hidden player",
                "hidden entry exposes its username")
            check(!response.toString().contains(dataSource.hiddenUuid) && !response.toString().contains(dataSource.hiddenName),
                "hidden identity leaked in the API payload")
        }

        dataSource.sql.clear()
        AwardDbWriter(dataSource, logger).recomputeMedals("7")
        check(dataSource.sql.size == 2, "medal recomputation must reset then rank")
        check(dataSource.sql[0].contains("set medal = 0"), "stale alt medals are not cleared")
        check(dataSource.sql[1].contains("player_alts"), "alt accounts can still consume medal positions")
        check(dataSource.sql[1].contains("is_discord_member"), "departed Discord members can still consume medal positions")
        check(dataSource.sql[1].contains("eligible_player.awards_excluded = false"),
            "moderator-excluded players can still consume medal positions")
        check(dataSource.sql[1].contains("last_mc_login_at"), "inactive players can still consume medal positions")

        dataSource.sql.clear()
        AwardDbWriter(dataSource, logger).recomputeAllMedals()
        check(dataSource.sql.size == 3, "all-season medal recomputation must load, reset and rank each season")
        check(dataSource.sql[2].contains("last_mc_login_at"), "reactivating a player does not apply the inactivity cutoff")
        check(dataSource.sql[2].contains("eligible_player.awards_excluded = false"),
            "reactivating a player bypasses their moderator exclusion")
    }

    private class CapturingDataSource : HikariDataSource() {
        val sql = ArrayList<String>()
        val visibility = ArrayList<Boolean>()
        val hiddenUuid = UUID.randomUUID().toString()
        val hiddenName = "Fixture_" + UUID.randomUUID()
        val visibleUuid = UUID.randomUUID().toString()
        val visibleName = "Fixture_" + UUID.randomUUID()

        override fun getConnection(): Connection = proxy(Connection::class.java) { _, method, args ->
            when (method.name) {
                "prepareStatement" -> preparedStatement(args!![0] as String)
                else -> defaultValue(method.returnType)
            }
        }

        private fun preparedStatement(rawSql: String): PreparedStatement {
            val normalised = rawSql.lowercase(java.util.Locale.getDefault()).replace(Regex("\\s+"), " ").trim()
            sql.add(normalised)
            var showHidden = false
            return proxy(PreparedStatement::class.java) { _, method, args ->
                when (method.name) {
                    "setBoolean" -> {
                        showHidden = args!![1] as Boolean
                        visibility.add(showHidden)
                        null
                    }
                    "executeQuery" -> resultSet(normalised, showHidden)
                    "executeUpdate" -> 0
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun resultSet(sql: String, showHidden: Boolean): ResultSet {
            val rows: List<Map<String, Any>> = when {
                sql.contains("select distinct on (scores.award_id)") -> listOf(mapOf(
                    "award_id" to "test-award",
                    "minecraft_uuid" to if (showHidden) hiddenUuid else visibleUuid,
                    "minecraft_username" to if (showHidden) hiddenName else visibleName,
                    "nickname" to "Alias_" + if (showHidden) hiddenName else visibleName,
                    "hidden" to showHidden,
                    "best_score" to java.util.Random().nextDouble(1000.0, 2000.0)))
                showHidden && (sql.contains("case when ranked.rnk <= 3") || sql.contains("crowns.gold,")) -> listOf(mapOf(
                    "minecraft_uuid" to hiddenUuid, "minecraft_username" to hiddenName,
                    "nickname" to "Alias_" + hiddenName, "hidden" to true,
                    "score" to java.util.Random().nextDouble(1000.0, 2000.0),
                    "rnk" to 1, "medal" to 1, "gold" to 2,
                    "silver" to 1, "bronze" to 0, "crown_score" to 13))
                sql.contains("select distinct season from player_award_scores") -> listOf(mapOf("season" to "7"))
                sql.contains("from awards where id = ?") || sql.contains("from awards where enabled = true") -> listOf(mapOf(
                    "id" to "test-award", "title" to "Test Award", "description" to "Test",
                    "unit" to "int", "bucket" to "misc", "icon" to "test.png"))
                sql.contains("case when ranked.rnk <= 3") -> listOf(
                    mapOf("minecraft_uuid" to "primary-uuid", "minecraft_username" to "Primary", "score" to 42.0, "rnk" to 1, "medal" to 1),
                    mapOf("minecraft_uuid" to "second-uuid", "minecraft_username" to "Second", "score" to 42.0, "rnk" to 1, "medal" to 1),
                    mapOf("minecraft_uuid" to "third-uuid", "minecraft_username" to "Third", "score" to 41.0, "rnk" to 3, "medal" to 3))
                sql.contains("select award_id, score, rank from") -> listOf(mapOf("award_id" to "test-award", "score" to 1.0, "rank" to 1))
                else -> emptyList()
            }
            var index = -1
            return proxy(ResultSet::class.java) { _, method, args ->
                when (method.name) {
                    "next" -> ++index < rows.size
                    "getString" -> rows[index][args!![0]] as String?
                    "getBoolean" -> rows[index][args!![0]] == true
                    "getInt" -> (rows[index][args!![0]] as Number).toInt()
                    "getDouble" -> (rows[index][args!![0]] as Number).toDouble()
                    else -> defaultValue(method.returnType)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: java.lang.reflect.InvocationHandler): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

    private fun defaultValue(type: Class<*>): Any? {
        if (!type.isPrimitive || type == Void.TYPE) return null
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> throw AssertionError("Unhandled primitive " + type)
        }
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
