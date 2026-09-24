package crabcraft.net.crabUtilities.velocity.litebans

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID
import org.slf4j.Logger

/** Uses the LiteBans Database API reflectively so the plugin loads without LiteBans installed. */
class LiteBansInfractionService(private val logger: Logger) {
    @Volatile private var databaseGetMethod: Method? = null
    @Volatile private var prepareStatementMethod: Method? = null
    @Volatile private var unavailableLogged = false

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    fun getInfractionsJson(uuid: String, requestedLimit: Int): JsonObject {
        val limit = requestedLimit.coerceIn(1, MAX_LIMIT)
        val normalisedUuid = UUID.fromString(uuid).toString()
        val compactUuid = normalisedUuid.replace("-", "")
        val database = getDatabase()
        val infractions = ArrayList<Infraction>()
        for (source in SOURCES) infractions.addAll(querySource(database, source, normalisedUuid, compactUuid, limit))
        infractions.sortWith(compareByDescending { it.createdAtMs })
        val count = minOf(limit, infractions.size)
        val output = JsonArray()
        for (i in 0 until count) output.add(toJson(infractions[i]))
        return JsonObject().apply {
            addProperty("uuid", normalisedUuid)
            addProperty("count", count)
            add("infractions", output)
        }
    }

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    fun getActivePunishmentsJson(normalizedUuids: Collection<String>): JsonObject {
        val output = JsonArray()
        for (uuid in getActivePunishedUuids(normalizedUuids)) output.add(uuid)
        return JsonObject().apply {
            addProperty("count", output.size())
            add("punished_uuids", output)
        }
    }

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    fun getActivePunishedUuids(normalizedUuids: Collection<String>): Set<String> {
        val lookup = buildUuidLookup(normalizedUuids)
        val punished = LinkedHashSet<String>()
        if (lookup.isNotEmpty()) {
            val database = getDatabase()
            for (source in ACTIVE_PUNISHMENT_SOURCES) punished.addAll(
                queryActivePunishments(database, source, lookup, false)
            )
        }
        return punished
    }

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    fun getAllActivePunishedUuids(): Set<String> {
        val punished = LinkedHashSet<String>()
        val database = getDatabase()
        for (source in ACTIVE_PUNISHMENT_SOURCES) punished.addAll(
            queryActivePunishments(database, source, emptyMap(), true)
        )
        return punished
    }

    private fun querySource(
        database: Any,
        source: Source,
        uuid: String,
        compactUuid: String,
        limit: Int,
    ): List<Infraction> {
        val sql = "SELECT * FROM {${source.tableToken}} WHERE uuid = ? OR uuid = ? ORDER BY time DESC LIMIT ?"
        prepareStatement(database, sql).use { statement ->
            setQueryTimeout(statement)
            statement.setString(1, uuid)
            statement.setString(2, compactUuid)
            statement.setInt(3, limit)
            statement.executeQuery().use { result ->
                val columns = columns(result.metaData)
                val rows = ArrayList<Infraction>()
                while (result.next()) rows.add(readInfraction(source.type, columns, result))
                return rows
            }
        }
    }

    private fun queryActivePunishments(
        database: Any,
        source: Source,
        uuidLookup: Map<String, String>,
        allPlayers: Boolean,
    ): Set<String> {
        val nowEpochSeconds = System.currentTimeMillis() / 1000L
        val sql =
            "SELECT uuid, until, removed_by_name, removed_by_date FROM {${source.tableToken}} WHERE active = 1" +
                if (allPlayers) "" else " AND uuid IN (${placeholders(uuidLookup.size)})"
        prepareStatement(database, sql).use { statement ->
            setQueryTimeout(statement)
            var index = 1
            for (uuid in uuidLookup.keys) statement.setString(index++, uuid)
            statement.executeQuery().use { result ->
                val columns = columns(result.metaData)
                val rows = LinkedHashSet<String>()
                while (result.next()) {
                    if (!isCurrentPunishment(columns, result, nowEpochSeconds)) continue
                    val rawUuid = result.getString("uuid") ?: continue
                    val normalised =
                        if (allPlayers) normalizeStoredUuid(rawUuid) else uuidLookup[rawUuid.lowercase(Locale.ROOT)]
                    if (normalised != null) rows.add(normalised)
                }
                return rows
            }
        }
    }

    private fun prepareStatement(database: Any, sql: String): PreparedStatement {
        try {
            val method =
                prepareStatementMethod
                    ?: database.javaClass.getMethod("prepareStatement", String::class.java).also {
                        prepareStatementMethod = it
                    }
            return method.invoke(database, sql) as PreparedStatement
        } catch (e: ReflectiveOperationException) {
            val cause = if (e is InvocationTargetException) e.cause else e
            if (cause is SQLException) throw cause
            throw LiteBansUnavailableException(cause)
        }
    }

    private fun getDatabase(): Any {
        try {
            val method =
                databaseGetMethod
                    ?: Class.forName("litebans.api.Database").getMethod("get").also { databaseGetMethod = it }
            val database = method.invoke(null) ?: throw LiteBansUnavailableException("LiteBans database is not ready")
            unavailableLogged = false
            return database
        } catch (e: ClassNotFoundException) {
            logUnavailableOnce("LiteBans API is not present; infractions endpoint will return 503")
            throw LiteBansUnavailableException(e)
        } catch (e: ReflectiveOperationException) {
            val cause = if (e is InvocationTargetException) e.cause else e
            logUnavailableOnce("LiteBans API is not available; infractions endpoint will return 503")
            throw LiteBansUnavailableException(cause)
        }
    }

    private fun logUnavailableOnce(message: String) {
        if (!unavailableLogged) {
            logger.info(message)
            unavailableLogged = true
        }
    }

    private data class Source(val type: String, val tableToken: String)

    private data class Infraction(
        val type: String,
        val id: Long,
        val reason: String?,
        val staff: String?,
        val createdAtMs: Long,
        val expiresAtMs: Long?,
        val active: Boolean?,
        val removed: Boolean,
        val removedBy: String?,
        val removedAtMs: Long?,
    )

    class LiteBansUnavailableException : Exception {
        constructor(message: String) : super(message)

        constructor(cause: Throwable?) : super(cause)
    }

    companion object {
        private const val MAX_LIMIT = 25
        private const val QUERY_TIMEOUT_SECONDS = 5
        private val SOURCES =
            arrayOf(
                Source("ban", "bans"),
                Source("mute", "mutes"),
                Source("warning", "warnings"),
                Source("kick", "kicks"),
            )
        private val ACTIVE_PUNISHMENT_SOURCES = arrayOf(Source("ban", "bans"), Source("mute", "mutes"))

        private fun buildUuidLookup(uuids: Collection<String>): Map<String, String> {
            val lookup = LinkedHashMap<String, String>()
            for (uuid in uuids) {
                val normalised = UUID.fromString(uuid).toString()
                lookup[normalised] = normalised
                lookup[normalised.replace("-", "")] = normalised
            }
            return lookup
        }

        private fun placeholders(count: Int): String =
            buildString(count * 2) {
                for (i in 0 until count) {
                    if (i > 0) append(',')
                    append('?')
                }
            }

        private fun normalizeStoredUuid(uuid: String?): String? {
            if (uuid == null) return null
            var value = uuid.trim()
            return try {
                if (value.length == 32)
                    value =
                        value.substring(0, 8) +
                            "-" +
                            value.substring(8, 12) +
                            "-" +
                            value.substring(12, 16) +
                            "-" +
                            value.substring(16, 20) +
                            "-" +
                            value.substring(20)
                UUID.fromString(value).toString()
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun isCurrentPunishment(columns: Map<String, Int>, result: ResultSet, nowEpochSeconds: Long): Boolean {
            val removedBy = getString(columns, result, "removed_by_name")
            val removedAt = getNullableTimestampMillis(columns, result, "removed_by_date")
            if (!removedBy.isNullOrBlank() || (removedAt != null && removedAt > 0L)) return false
            val expiresAt = getNullableTimestampMillis(columns, result, "until")
            return expiresAt == null || expiresAt <= 0L || toEpochSeconds(expiresAt) > nowEpochSeconds
        }

        private fun readInfraction(type: String, columns: Map<String, Int>, result: ResultSet): Infraction {
            val createdAtMs = getNullableTimestampMillis(columns, result, "time") ?: 0L
            val expiresAtMs = getNullableTimestampMillis(columns, result, "until")?.takeIf { it > 0L }
            val active = getNullableBoolean(columns, result, "active")
            val removedBy = getString(columns, result, "removed_by_name")
            val removedAtMs = getNullableTimestampMillis(columns, result, "removed_by_date")
            val removed = (removedAtMs != null && removedAtMs > 0L) || removedBy != null
            return Infraction(
                type,
                getNullableLong(columns, result, "id") ?: 0L,
                getString(columns, result, "reason"),
                getString(columns, result, "banned_by_name"),
                createdAtMs,
                expiresAtMs,
                active,
                removed,
                removedBy,
                removedAtMs,
            )
        }

        private fun setQueryTimeout(statement: PreparedStatement) {
            try {
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
            } catch (_: SQLException) {
                // Some JDBC drivers do not support per-statement timeouts.
            }
        }

        private fun toJson(infraction: Infraction) =
            JsonObject().apply {
                addProperty("type", infraction.type)
                addProperty("id", infraction.id)
                addNullable(this, "reason", infraction.reason)
                addNullable(this, "staff", infraction.staff)
                addProperty("created_at", toEpochSeconds(infraction.createdAtMs))
                addNullableEpoch(this, "expires_at", infraction.expiresAtMs)
                if (infraction.active == null) add("active", JsonNull.INSTANCE)
                else addProperty("active", infraction.active)
                addProperty("removed", infraction.removed)
                addNullable(this, "removed_by", infraction.removedBy)
                addNullableEpoch(this, "removed_at", infraction.removedAtMs)
            }

        private fun addNullable(obj: JsonObject, key: String, value: String?) {
            if (value.isNullOrBlank()) obj.add(key, JsonNull.INSTANCE) else obj.addProperty(key, value)
        }

        private fun addNullableEpoch(obj: JsonObject, key: String, value: Long?) {
            if (value == null || value <= 0L) obj.add(key, JsonNull.INSTANCE)
            else obj.addProperty(key, toEpochSeconds(value))
        }

        private fun toEpochSeconds(value: Long) = if (value > 10_000_000_000L) value / 1000L else value

        private fun columns(meta: ResultSetMetaData): Map<String, Int> {
            val columns = HashMap<String, Int>()
            for (index in 1..meta.columnCount) columns[meta.getColumnLabel(index).lowercase(Locale.ROOT)] = index
            return columns
        }

        private fun getString(columns: Map<String, Int>, result: ResultSet, column: String): String? {
            val index = columns[column] ?: return null
            val value = result.getString(index)
            return if (result.wasNull()) null else value
        }

        private fun getNullableLong(columns: Map<String, Int>, result: ResultSet, column: String): Long? {
            val index = columns[column] ?: return null
            val value = result.getLong(index)
            return if (result.wasNull()) null else value
        }

        private fun getNullableTimestampMillis(columns: Map<String, Int>, result: ResultSet, column: String): Long? {
            val index = columns[column] ?: return null
            val value = result.getObject(index)
            if (value == null || result.wasNull()) return null
            when (value) {
                is Number -> return value.toLong()
                is Timestamp -> return value.time
                is java.util.Date -> return value.time
                is LocalDateTime -> return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                is OffsetDateTime -> return value.toInstant().toEpochMilli()
                is ZonedDateTime -> return value.toInstant().toEpochMilli()
            }
            val text = value.toString().trim()
            if (text.isEmpty()) return null
            try {
                return text.toLong()
            } catch (_: NumberFormatException) {
                // H2 LiteBans date fields may be yyyy-MM-dd HH:mm:ss.SSS.
            }
            return try {
                Timestamp.valueOf(text).time
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun getNullableBoolean(columns: Map<String, Int>, result: ResultSet, column: String): Boolean? {
            val index = columns[column] ?: return null
            val value = result.getBoolean(index)
            return if (result.wasNull()) null else value
        }
    }
}
