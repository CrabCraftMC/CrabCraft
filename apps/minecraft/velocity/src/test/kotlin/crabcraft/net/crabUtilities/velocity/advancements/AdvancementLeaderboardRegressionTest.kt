package crabcraft.net.crabUtilities.velocity.advancements

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.lang.reflect.Proxy
import java.sql.Array as SqlArray
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

object AdvancementLeaderboardRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val dataSource = CapturingDataSource()
        val logger = proxy(Logger::class.java) { _, method, _ -> defaultValue(method.returnType) }
        val registry = AdvancementRegistry(logger)
        val queries = AdvancementQueryService(dataSource, logger, registry)

        queries.getAdvancementLeaderboard("7", 100, 0, null)
        check(dataSource.sql.size == 2, "expected both leaderboard queries to run")
        check(dataSource.sql.all { sql -> sql.contains("= any (?)") },
            "a leaderboard query does not filter against the advancement registry")
        check(dataSource.sql.all { sql -> sql.contains("is_discord_member") },
            "a leaderboard query does not exclude departed Discord members")
        check(dataSource.sql.all { sql -> sql.contains("last_mc_login_at") },
            "a leaderboard query does not exclude inactive players")
        check(dataSource.sql.all { sql -> sql.contains("2592000") },
            "a leaderboard query does not use the 30-day window")
        check(dataSource.boundAdvancementIds.size == 2, "the registry IDs were not bound to both leaderboard queries")
        check(dataSource.boundAdvancementIds.all { ids -> ids.size == 126 },
            "the full leaderboard did not bind all 126 registered advancements")
        check(dataSource.boundAdvancementIds.none { ids -> ids.contains("custom:extra_advancement") },
            "an unregistered advancement could contribute to the leaderboard")

        dataSource.clear()
        queries.getAdvancementLeaderboard("7", 100, 0, "adventure")
        check(dataSource.boundAdvancementIds.all { ids -> ids.size == 47 },
            "the Adventure leaderboard did not bind its 47 registered advancements")
        check(dataSource.boundAdvancementIds.flatten().all { id -> id.startsWith("minecraft:adventure/") },
            "the Adventure leaderboard bound an advancement from another category")
    }

    private class CapturingDataSource : HikariDataSource() {
        val sql = ArrayList<String>()
        val boundAdvancementIds = ArrayList<List<String>>()

        override fun getConnection(): Connection = proxy(Connection::class.java) { _, method, args ->
            when (method.name) {
                "createArrayOf" -> sqlArray(args!![1] as Array<*>)
                "prepareStatement" -> preparedStatement(args!![0] as String)
                else -> defaultValue(method.returnType)
            }
        }

        private fun sqlArray(values: Array<*>): SqlArray {
            val ids = values.map { it.toString() }
            return proxy(SqlArray::class.java) { _, method, _ ->
                when (method.name) {
                    "getArray" -> values
                    "toString" -> ids.toString()
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun preparedStatement(rawSql: String): PreparedStatement {
            val normalised = rawSql.lowercase(java.util.Locale.getDefault()).replace(Regex("\\s+"), " ").trim()
            sql.add(normalised)
            return proxy(PreparedStatement::class.java) { _, method, args ->
                when (method.name) {
                    "setArray" -> {
                        val values = (args!![1] as SqlArray).array as Array<*>
                        boundAdvancementIds.add(values.map { it.toString() })
                        null
                    }
                    "executeQuery" -> resultSet(normalised.startsWith("select count"))
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun resultSet(countQuery: Boolean): ResultSet {
            var unread = countQuery
            return proxy(ResultSet::class.java) { _, method, _ ->
                when (method.name) {
                    "next" -> {
                        val hasNext = unread
                        unread = false
                        hasNext
                    }
                    "getInt" -> 1
                    else -> defaultValue(method.returnType)
                }
            }
        }

        fun clear() {
            sql.clear()
            boundAdvancementIds.clear()
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
