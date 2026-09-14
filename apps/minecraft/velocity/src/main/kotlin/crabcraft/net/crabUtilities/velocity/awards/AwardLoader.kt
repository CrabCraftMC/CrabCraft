package crabcraft.net.crabUtilities.velocity.awards

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.SQLException
import java.util.Collections

/** Loads enabled award reader definitions from the editable PostgreSQL awards table. */
class AwardLoader private constructor() {
    companion object {
        private val SELECT_SQL = """
        SELECT id, reader_type, reader_path, reader_patterns
        FROM awards
        WHERE enabled = true
        """.trimIndent() + "\n"
        private val GSON = Gson()
        private val STRING_LIST_TYPE = object : TypeToken<List<String>>() {}.type

        @JvmStatic fun loadAll(dataSource: HikariDataSource, logger: Logger): Map<String, AwardDefinition> {
            val out = HashMap<String, AwardDefinition>()
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(SELECT_SQL).use { stmt ->
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val id = rs.getString("id")
                                val readerType = rs.getString("reader_type")
                                val readerPathJson = rs.getString("reader_path")
                                val readerPatternsJson = rs.getString("reader_patterns")
                                if (id == null || readerType == null || readerPathJson == null) {
                                    logger.warn("Skipping award with missing required columns: id={}", id)
                                    continue
                                }
                                val def = AwardDefinition()
                                def.id = id
                                val reader = AwardDefinition.Reader()
                                def.reader = reader
                                reader.type = readerType
                                try {
                                    reader.path = GSON.fromJson(readerPathJson, STRING_LIST_TYPE)
                                    reader.patterns = if (readerPatternsJson == null) null else GSON.fromJson(readerPatternsJson, STRING_LIST_TYPE)
                                } catch (e: Exception) {
                                    logger.warn("Skipping award {} with malformed reader spec", id, e)
                                    continue
                                }
                                out[id] = def
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                logger.error("Failed to load award definitions from database", e)
                return Collections.emptyMap()
            }
            return Collections.unmodifiableMap(out)
        }
    }
}
