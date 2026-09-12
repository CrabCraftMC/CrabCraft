package crabcraft.net.crabUtilities.velocity.db

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.SQLException

/** Postgres store for per-player /settings preferences, stored verbatim as JSON text. */
class PlayerSettingsRepository(private val dataSource: HikariDataSource, private val logger: Logger) {
    init { ensureSchema() }

    private fun ensureSchema() {
        try {
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt -> stmt.execute(CREATE_TABLE_SQL) }
            }
        } catch (e: SQLException) {
            logger.error("Failed to ensure player_settings schema", e)
        }
    }

    /** Returns the stored settings JSON for a player, or null if none. */
    fun load(uuid: String?): String? {
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_SQL).use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.executeQuery().use { rs ->
                        return if (rs.next()) rs.getString("settings") else null
                    }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to read settings for {}", uuid, e)
            return null
        }
    }

    /** Inserts or updates a player's settings JSON. */
    fun save(uuid: String?, settingsJson: String?) {
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(UPSERT_SQL).use { stmt ->
                    stmt.setString(1, uuid)
                    stmt.setString(2, settingsJson)
                    stmt.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to save settings for {}", uuid, e)
        }
    }

    companion object {
    private val CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_settings (
                minecraft_uuid TEXT PRIMARY KEY,
                settings TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent() + "\n"

    private val SELECT_SQL =
            "SELECT settings FROM player_settings WHERE minecraft_uuid = ?"

    private val UPSERT_SQL = """
            INSERT INTO player_settings (minecraft_uuid, settings, updated_at)
            VALUES (?, ?, EXTRACT(EPOCH FROM NOW())::INTEGER)
            ON CONFLICT (minecraft_uuid) DO UPDATE SET
                settings = EXCLUDED.settings,
                updated_at = EXTRACT(EPOCH FROM NOW())::INTEGER
            """.trimIndent() + "\n"

    }
}
