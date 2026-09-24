package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariDataSource
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import org.slf4j.Logger

/** Seeds bundled award definitions only into an empty table, preserving later configuration changes. */
class AwardSeeder {
    private constructor() {}

    companion object {
        private val RESOURCE: String = "/crabcraft/awards.json"

        private val COUNT_SQL: String = "SELECT COUNT(*) FROM awards"

        private val INSERT_SQL: String =
            ("""
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
            """
                .trimIndent() + "\n")

        private val GSON: Gson = Gson()

        @JvmStatic
        fun seedIfEmpty(dataSource: HikariDataSource, logger: Logger) {
            var existing: Long
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(COUNT_SQL).use { count ->
                        count.executeQuery().use { rs ->
                            rs.next()
                            existing = rs.getLong(1)
                        }
                    }
                }
            } catch (e: SQLException) {
                logger.error("Award seed: count probe failed", e)
                return
            }
            if (existing > 0) return

            var rows: JsonArray? = null
            try {
                AwardSeeder::class.java.getResourceAsStream(RESOURCE).use { input ->
                    if (input == null) {
                        logger.warn("Award seed: resource {} missing from plugin JAR", RESOURCE)
                        return
                    }
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                        rows = GSON.fromJson(reader, JsonArray::class.java)
                    }
                }
            } catch (e: Exception) {
                logger.error("Award seed: failed to read bundled JSON", e)
                return
            }
            if (rows == null || rows!!.size() == 0) return

            var seeded: Int = 0
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(INSERT_SQL).use { stmt ->
                        conn.autoCommit = false
                        try {
                            for (i in 0 until rows!!.size()) {
                                val r: JsonObject = rows!!.get(i).getAsJsonObject()
                                val reader: JsonObject = r.getAsJsonObject("reader")
                                stmt.setString(1, r.get("id").getAsString())
                                stmt.setString(2, r.get("title").getAsString())
                                stmt.setString(3, r.get("description").getAsString())
                                stmt.setString(4, r.get("unit").getAsString())
                                stmt.setString(5, r.get("bucket").getAsString())
                                stmt.setString(6, r.get("icon").getAsString())
                                stmt.setString(7, reader.get("type").getAsString())
                                stmt.setString(8, reader.getAsJsonArray("path").toString())
                                val patterns: JsonElement? = reader.get("patterns")
                                stmt.setString(9, patterns?.asJsonArray?.toString())
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
