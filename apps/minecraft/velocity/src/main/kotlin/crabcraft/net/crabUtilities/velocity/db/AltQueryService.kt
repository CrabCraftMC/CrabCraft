package crabcraft.net.crabUtilities.velocity.db

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.SQLException

class AltQueryService(private val dataSource: HikariDataSource, private val logger: Logger) {
    /**
     * Check if a Minecraft UUID is registered as an alt account.
     * Called once per proxy join — zero overhead when nobody is joining.
     */
    fun isAlt(minecraftUuid: String?): Boolean {
        val sql = "SELECT 1 FROM player_alts WHERE minecraft_uuid = ? LIMIT 1"
        try {
            dataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, minecraftUuid)
                    stmt.executeQuery().use { rs -> return rs.next() }
                }
            }
        } catch (e: SQLException) {
            logger.error("Failed to check alt status for {}", minecraftUuid, e)
            return false
        }
    }
}
