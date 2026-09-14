package crabcraft.net.crabUtilities.velocity.db

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

object LoginStreakLeaderboardEligibilityRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val dataSource = CapturingDataSource()
        val logger = proxy(Logger::class.java) { _, method, _ -> defaultValue(method.returnType) }
        val service = LoginStreakService(dataSource, logger, 6, 600)

        service.getLeaderboard(100, 0, false)
        service.getLeaderboard(100, 0, true)
        check(dataSource.leaderboardSql.size == 4, "expected count and list queries for both streak leaderboards")
        check(dataSource.leaderboardSql.all { sql -> sql.contains("is_discord_member") },
            "a streak leaderboard query does not exclude departed Discord members")
        check(dataSource.leaderboardSql.all { sql -> sql.contains("last_mc_login_at") },
            "a streak leaderboard query does not exclude inactive players")
        check(dataSource.leaderboardSql.all { sql -> sql.contains("2592000") },
            "a streak leaderboard query does not use the 30-day window")

        val now = 5_000_000L
        check(PostgresStatsWriter.isInactiveForLeaderboard(null, now), "a player with no recorded login should be inactive")
        check(PostgresStatsWriter.isInactiveForLeaderboard(now - 2_592_001L, now), "a player beyond 30 days should be inactive")
        check(!PostgresStatsWriter.isInactiveForLeaderboard(now - 2_592_000L, now),
            "a player exactly on the 30-day boundary should remain active")
        check(!PostgresStatsWriter.isInactiveForLeaderboard(now, now), "a player who just logged in should be active")
    }

    private class CapturingDataSource : HikariDataSource() {
        val leaderboardSql = ArrayList<String>()

        override fun getConnection(): Connection = proxy(Connection::class.java) { _, method, args ->
            when (method.name) {
                "createStatement" -> statement()
                "prepareStatement" -> preparedStatement(args!![0] as String)
                else -> defaultValue(method.returnType)
            }
        }

        private fun statement(): Statement = proxy(Statement::class.java) { _, method, _ -> defaultValue(method.returnType) }

        private fun preparedStatement(rawSql: String): PreparedStatement {
            val sql = rawSql.lowercase(java.util.Locale.getDefault()).replace(Regex("\\s+"), " ").trim()
            if (sql.contains("from player_login_streaks s")) leaderboardSql.add(sql)
            return proxy(PreparedStatement::class.java) { _, method, _ ->
                when (method.name) {
                    "executeQuery" -> resultSet()
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun resultSet(): ResultSet = proxy(ResultSet::class.java) { _, method, _ ->
            when (method.name) {
                "next" -> false
                else -> defaultValue(method.returnType)
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
