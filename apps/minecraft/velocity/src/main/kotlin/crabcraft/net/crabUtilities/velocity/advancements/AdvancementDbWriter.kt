package crabcraft.net.crabUtilities.velocity.advancements

import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class AdvancementDbWriter(private val dataSource: HikariDataSource, private val logger: Logger) {
    fun writeForPlayer(uuid: String?, season: String?, advancements: JsonObject?) {
        if (advancements == null || advancements.size() == 0) return
        try {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    upsertAdvancements(conn, uuid, season, advancements)
                    conn.commit()
                } catch (e: SQLException) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to write advancements for uuid={}", uuid, e)
        }
    }

    private fun upsertAdvancements(conn: Connection, uuid: String?, season: String?, advancements: JsonObject) {
        conn.prepareStatement(UPSERT_ADVANCEMENT).use { stmt ->
            for (entry in advancements.entrySet()) {
                val advId = entry.key
                if (advId == "DataVersion" || advId.startsWith("minecraft:recipes/") || !entry.value.isJsonObject) continue
                val adv = entry.value.asJsonObject
                val done = adv.has("done") && adv.get("done").asBoolean
                val completedAt = parseCompletedAt(adv)
                stmt.setString(1, uuid)
                stmt.setString(2, season)
                stmt.setString(3, advId)
                stmt.setBoolean(4, done)
                if (completedAt != null) stmt.setInt(5, completedAt) else stmt.setNull(5, java.sql.Types.INTEGER)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    companion object {
        private val UPSERT_ADVANCEMENT = """
        INSERT INTO player_advancements
            (minecraft_uuid, season, advancement_id, completed, completed_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (minecraft_uuid, season, advancement_id) DO UPDATE SET
            completed = EXCLUDED.completed,
            completed_at = COALESCE(EXCLUDED.completed_at, player_advancements.completed_at)
        """.trimIndent() + "\n"
        private val MC_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

        private fun parseCompletedAt(adv: JsonObject): Int? {
            if (!adv.has("criteria") || !adv.get("criteria").isJsonObject) return null
            val criteria = adv.getAsJsonObject("criteria")
            var earliest = Long.MAX_VALUE
            for (c in criteria.entrySet()) {
                if (!c.value.isJsonPrimitive) continue
                try {
                    val dt = LocalDateTime.parse(c.value.asString, MC_DATE_FORMAT)
                    val epoch = dt.toInstant(ZoneOffset.UTC).epochSecond
                    if (epoch < earliest) earliest = epoch
                } catch (ignored: Exception) {}
            }
            return if (earliest == Long.MAX_VALUE) null else earliest.toInt()
        }
    }
}
