package crabcraft.net.crabUtilities.velocity.litebans

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.slf4j.Logger
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

/**
 * Public-history adapter for LiteBans. Uses LiteBans' Database API reflectively
 * so CrabUtilities still loads when LiteBans is not installed.
 */
class LiteBansInfractionService(private val logger: Logger) {
    @Volatile private var databaseGetMethod: Method? = null
    @Volatile private var prepareStatementMethod: Method? = null
    @Volatile private var unavailableLogged = false

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    fun getInfractionsJson(uuid: String, requestedLimit: Int): JsonObject {
        val limit = maxOf(1, minOf(MAX_LIMIT, requestedLimit))
        val normalizedUuid = UUID.fromString(uuid).toString()
        val infractions = ArrayList<Infraction>()
        val compactUuid = normalizedUuid.replace("-", "")
        val database = getDatabase()
        for (source in SOURCES) infractions.addAll(querySource(database, source, normalizedUuid, compactUuid, limit))
        infractions.sortWith { first, second -> second.createdAtMs().compareTo(first.createdAtMs()) }

        val output = JsonArray()
        val count = minOf(limit, infractions.size)
        for (index in 0 until count) output.add(toJson(infractions[index]))
        val response = JsonObject()
        response.addProperty("uuid", normalizedUuid)
        response.addProperty("count", count)
        response.add("infractions", output)
        return response
    }

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    fun getActivePunishmentsJson(normalizedUuids: Collection<String>): JsonObject {
        val punishedUuids = getActivePunishedUuids(normalizedUuids)
        val output = JsonArray()
        for (uuid in punishedUuids) output.add(uuid)
        val response = JsonObject()
        response.addProperty("count", output.size())
        response.add("punished_uuids", output)
        return response
    }

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    fun getActivePunishedUuids(normalizedUuids: Collection<String>): Set<String> {
        val uuidLookup = buildUuidLookup(normalizedUuids)
        val punishedUuids = LinkedHashSet<String>()
        if (uuidLookup.isNotEmpty()) {
            val database = getDatabase()
            for (source in ACTIVE_PUNISHMENT_SOURCES) punishedUuids.addAll(queryActivePunishments(database, source, uuidLookup))
        }
        return punishedUuids
    }

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    fun getAllActivePunishedUuids(): Set<String> {
        val punishedUuids = LinkedHashSet<String>()
        val database = getDatabase()
        for (source in ACTIVE_PUNISHMENT_SOURCES) punishedUuids.addAll(queryAllActivePunishments(database, source))
        return punishedUuids
    }

    @Throws(SQLException::class, LiteBansUnavailableException::class)
    private fun querySource(database: Any, source: Source, uuid: String, compactUuid: String, limit: Int): List<Infraction> {
        val sql = "SELECT * FROM {" + source.tableToken() + "} " + "WHERE uuid = ? OR uuid = ? ORDER BY time DESC LIMIT ?"
        prepareStatement(database, sql).use { statement ->
            setQueryTimeout(statement)
            statement.setString(1, uuid)
            statement.setString(2, compactUuid)
            statement.setInt(3, limit)
            statement.executeQuery().use { result ->
                val columns = columns(result.metaData)
                val rows = ArrayList<Infraction>()
                while (result.next()) rows.add(readInfraction(source.type(), columns, result))
                return rows
            }
        }
    }

    @Throws(SQLException::class, LiteBansUnavailableException::class)
    private fun queryActivePunishments(database: Any, source: Source, uuidLookup: Map<String, String>): Set<String> {
        val nowEpochSeconds = System.currentTimeMillis() / 1000L
        val sql = "SELECT uuid, until, removed_by_name, removed_by_date FROM {" + source.tableToken() + "} " +
            "WHERE active = 1 AND uuid IN (" + placeholders(uuidLookup.size) + ")"
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
                    val normalizedUuid = uuidLookup[rawUuid.lowercase(Locale.ROOT)]
                    if (normalizedUuid != null) rows.add(normalizedUuid)
                }
                return rows
            }
        }
    }

    @Throws(SQLException::class, LiteBansUnavailableException::class)
    private fun queryAllActivePunishments(database: Any, source: Source): Set<String> {
        val nowEpochSeconds = System.currentTimeMillis() / 1000L
        val sql = "SELECT uuid, until, removed_by_name, removed_by_date FROM {" + source.tableToken() + "} WHERE active = 1"
        prepareStatement(database, sql).use { statement ->
            setQueryTimeout(statement)
            statement.executeQuery().use { result ->
                val columns = columns(result.metaData)
                val rows = LinkedHashSet<String>()
                while (result.next()) {
                    if (!isCurrentPunishment(columns, result, nowEpochSeconds)) continue
                    val normalizedUuid = normalizeStoredUuid(result.getString("uuid"))
                    if (normalizedUuid != null) rows.add(normalizedUuid)
                }
                return rows
            }
        }
    }

    @Throws(LiteBansUnavailableException::class, SQLException::class)
    private fun prepareStatement(database: Any, sql: String): PreparedStatement {
        try {
            var method = prepareStatementMethod
            if (method == null) {
                method = database.javaClass.getMethod("prepareStatement", String::class.java)
                prepareStatementMethod = method
            }
            return method.invoke(database, sql) as PreparedStatement
        } catch (e: ReflectiveOperationException) {
            val cause = if (e is InvocationTargetException) e.cause else e
            if (cause is SQLException) throw cause
            throw LiteBansUnavailableException(cause)
        }
    }

    @Throws(LiteBansUnavailableException::class)
    private fun getDatabase(): Any {
        try {
            var method = databaseGetMethod
            if (method == null) {
                val databaseClass = Class.forName("litebans.api.Database")
                method = databaseClass.getMethod("get")
                databaseGetMethod = method
            }
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

    private data class Source(private val type: String, private val tableToken: String) {
        fun type(): String = type
        fun tableToken(): String = tableToken
    }

    private data class Infraction(
        private val type: String,
        private val id: Long,
        private val reason: String?,
        private val staff: String?,
        private val createdAtMs: Long,
        private val expiresAtMs: Long?,
        private val active: Boolean?,
        private val removed: Boolean,
        private val removedBy: String?,
        private val removedAtMs: Long?
    ) {
        fun type(): String = type
        fun id(): Long = id
        fun reason(): String? = reason
        fun staff(): String? = staff
        fun createdAtMs(): Long = createdAtMs
        fun expiresAtMs(): Long? = expiresAtMs
        fun active(): Boolean? = active
        fun removed(): Boolean = removed
        fun removedBy(): String? = removedBy
        fun removedAtMs(): Long? = removedAtMs
    }

    class LiteBansUnavailableException : Exception {
        constructor(message: String) : super(message)
        constructor(cause: Throwable?) : super(cause)
    }

    companion object {
        private const val MAX_LIMIT = 25
        private val SOURCES = arrayOf(Source("ban", "bans"), Source("mute", "mutes"), Source("warning", "warnings"), Source("kick", "kicks"))
        private val ACTIVE_PUNISHMENT_SOURCES = arrayOf(Source("ban", "bans"), Source("mute", "mutes"))
        private const val QUERY_TIMEOUT_SECONDS = 5

        private fun buildUuidLookup(normalizedUuids: Collection<String>): Map<String, String> {
            val lookup = LinkedHashMap<String, String>()
            for (uuid in normalizedUuids) {
                val normalized = UUID.fromString(uuid).toString()
                lookup[normalized] = normalized
                lookup[normalized.replace("-", "")] = normalized
            }
            return lookup
        }

        private fun placeholders(count: Int): String {
            val builder = StringBuilder(count * 2)
            for (index in 0 until count) {
                if (index > 0) builder.append(',')
                builder.append('?')
            }
            return builder.toString()
        }

        private fun normalizeStoredUuid(uuid: String?): String? {
            if (uuid == null) return null
            var value = uuid.trim { it <= ' ' }
            return try {
                if (value.length == 32) {
                    value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16) + "-" +
                        value.substring(16, 20) + "-" + value.substring(20)
                }
                UUID.fromString(value).toString()
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        @Throws(SQLException::class)
        private fun isCurrentPunishment(columns: Map<String, Int>, result: ResultSet, nowEpochSeconds: Long): Boolean {
            val removedBy = getString(columns, result, "removed_by_name")
            val removedAt = getNullableTimestampMillis(columns, result, "removed_by_date")
            if ((removedBy != null && !removedBy.isBlank()) || (removedAt != null && removedAt > 0L)) return false
            val expiresAt = getNullableTimestampMillis(columns, result, "until")
            return expiresAt == null || expiresAt <= 0L || toEpochSeconds(expiresAt) > nowEpochSeconds
        }

        @Throws(SQLException::class)
        private fun readInfraction(type: String, columns: Map<String, Int>, result: ResultSet): Infraction {
            val createdAtMs = getNullableTimestampMillis(columns, result, "time") ?: 0L
            var expiresAtMs = getNullableTimestampMillis(columns, result, "until")
            if (expiresAtMs != null && expiresAtMs <= 0L) expiresAtMs = null
            val active = getNullableBoolean(columns, result, "active")
            val removedBy = getString(columns, result, "removed_by_name")
            val removedAtMs = getNullableTimestampMillis(columns, result, "removed_by_date")
            val removed = (removedAtMs != null && removedAtMs > 0L) || removedBy != null
            return Infraction(type, getLong(columns, result, "id", 0L), getString(columns, result, "reason"),
                getString(columns, result, "banned_by_name"), createdAtMs, expiresAtMs, active, removed, removedBy, removedAtMs)
        }

        private fun setQueryTimeout(statement: PreparedStatement) {
            try {
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
            } catch (ignored: SQLException) {
                // Some JDBC drivers do not support per-statement timeouts.
            }
        }

        private fun toJson(infraction: Infraction): JsonObject {
            val obj = JsonObject()
            obj.addProperty("type", infraction.type())
            obj.addProperty("id", infraction.id())
            addNullable(obj, "reason", infraction.reason())
            addNullable(obj, "staff", infraction.staff())
            obj.addProperty("created_at", toEpochSeconds(infraction.createdAtMs()))
            addNullableEpoch(obj, "expires_at", infraction.expiresAtMs())
            if (infraction.active() == null) obj.add("active", JsonNull.INSTANCE) else obj.addProperty("active", infraction.active())
            obj.addProperty("removed", infraction.removed())
            addNullable(obj, "removed_by", infraction.removedBy())
            addNullableEpoch(obj, "removed_at", infraction.removedAtMs())
            return obj
        }

        private fun addNullable(obj: JsonObject, key: String, value: String?) {
            if (value == null || value.isBlank()) obj.add(key, JsonNull.INSTANCE) else obj.addProperty(key, value)
        }

        private fun addNullableEpoch(obj: JsonObject, key: String, value: Long?) {
            if (value == null || value <= 0L) obj.add(key, JsonNull.INSTANCE) else obj.addProperty(key, toEpochSeconds(value))
        }

        private fun toEpochSeconds(value: Long): Long = if (value > 10_000_000_000L) value / 1000L else value

        @Throws(SQLException::class)
        private fun columns(meta: ResultSetMetaData): Map<String, Int> {
            val columns = HashMap<String, Int>()
            for (index in 1..meta.columnCount) columns[meta.getColumnLabel(index).lowercase(Locale.ROOT)] = index
            return columns
        }

        @Throws(SQLException::class)
        private fun getString(columns: Map<String, Int>, result: ResultSet, column: String): String? {
            val index = columns[column] ?: return null
            val value = result.getString(index)
            return if (result.wasNull()) null else value
        }

        @Throws(SQLException::class)
        private fun getLong(columns: Map<String, Int>, result: ResultSet, column: String, fallback: Long): Long =
            getNullableLong(columns, result, column) ?: fallback

        @Throws(SQLException::class)
        private fun getNullableLong(columns: Map<String, Int>, result: ResultSet, column: String): Long? {
            val index = columns[column] ?: return null
            val value = result.getLong(index)
            return if (result.wasNull()) null else value
        }

        @Throws(SQLException::class)
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
            val text = value.toString().trim { it <= ' ' }
            if (text.isEmpty()) return null
            try {
                return text.toLong()
            } catch (ignored: NumberFormatException) {
                // H2 LiteBans date fields can be formatted as yyyy-MM-dd HH:mm:ss.SSS.
            }
            return try {
                Timestamp.valueOf(text).time
            } catch (ignored: IllegalArgumentException) {
                null
            }
        }

        @Throws(SQLException::class)
        private fun getNullableBoolean(columns: Map<String, Int>, result: ResultSet, column: String): Boolean? {
            val index = columns[column] ?: return null
            val value = result.getBoolean(index)
            return if (result.wasNull()) null else value
        }
    }
}
