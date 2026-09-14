package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.SQLException

/** Seeds bundled awards JSON only when the table is empty, preserving runtime edits. */
class AwardSeeder private constructor() {
    companion object {
        private const val RESOURCE = "/crabcraft/awards.json"
        private const val COUNT_SQL = "SELECT COUNT(*) FROM awards"
        private val INSERT_SQL = """
        INSERT INTO awards (
            id, title, description, unit, bucket, icon,
            reader_type, reader_path, reader_patterns,
            sort_order, enabled, created_at, updated_at
        ) VALUES (
            ?, ?, ?, ?, ?, ?,
            ?, ?::jsonb, ?::jsonb,
            ?, TRUE,
            EXTRACT(EPOCH FROM NOW())::INTEGER,
            EXTRACT(EPOCH FROM NOW())::INTEGER
        )
        ON CONFLICT (id) DO NOTHING
        """.trimIndent() + "\n"
        private val GSON = Gson()

        @JvmStatic fun seedIfEmpty(dataSource: HikariDataSource, logger: Logger) {
            val existing: Long
            try {
                existing = dataSource.connection.use { conn ->
                    conn.prepareStatement(COUNT_SQL).use { count ->
                        count.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                    }
                }
            } catch (e: SQLException) {
                logger.error("Award seed: count probe failed", e)
                return
            }
            if (existing > 0) return
            val rows: JsonArray?
            try {
                rows = AwardSeeder::class.java.getResourceAsStream(RESOURCE).use { input ->
                    if (input == null) {
                        logger.warn("Award seed: resource {} missing from plugin JAR", RESOURCE)
                        return
                    }
                    input.bufferedReader(Charsets.UTF_8).use { reader -> GSON.fromJson(reader, JsonArray::class.java) }
                }
            } catch (e: Exception) {
                logger.error("Award seed: failed to read bundled JSON", e)
                return
            }
            if (rows == null || rows.size() == 0) return
            var seeded = 0
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(INSERT_SQL).use { stmt ->
                        conn.autoCommit = false
                        try {
                            for (i in 0 until rows.size()) {
                                val r = rows.get(i).asJsonObject
                                val reader = r.getAsJsonObject("reader")
                                stmt.setString(1, r.get("id").asString)
                                stmt.setString(2, r.get("title").asString)
                                stmt.setString(3, r.get("description").asString)
                                stmt.setString(4, r.get("unit").asString)
                                stmt.setString(5, r.get("bucket").asString)
                                stmt.setString(6, r.get("icon").asString)
                                stmt.setString(7, reader.get("type").asString)
                                stmt.setString(8, reader.getAsJsonArray("path").toString())
                                val patterns = reader.get("patterns")
                                stmt.setString(9, if (patterns == null) null else patterns.asJsonArray.toString())
                                stmt.setInt(10, i)
                                stmt.addBatch()
                                seeded++
                            }
                            stmt.executeBatch()
                            conn.commit()
                        } catch (e: SQLException) {
                            conn.rollback()
                            throw e
                        } finally {
                            conn.autoCommit = true
                        }
                    }
                }
            } catch (e: SQLException) {
                logger.error("Award seed: insert failed", e)
                return
            }
            logger.info("Seeded {} awards into empty table", seeded)
        }
    }
}
