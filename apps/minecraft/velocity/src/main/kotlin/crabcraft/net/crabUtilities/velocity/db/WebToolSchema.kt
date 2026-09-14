package crabcraft.net.crabUtilities.velocity.db

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.Logger
import java.sql.SQLException

/**
 * Keeps web-tool tables in lockstep with {@code packages/db/src/schema.ts}.
 * Velocity does not query these rows; it creates the schema so either runtime
 * can initialise a fresh database without introducing Drizzle drift.
 */
class WebToolSchema private constructor() {
    companion object {
        private val CREATE_BLOCK_GRADIENT_SHARES_SQL = """
            CREATE TABLE IF NOT EXISTS block_gradient_shares (
                id TEXT PRIMARY KEY,
                version INTEGER NOT NULL DEFAULT 1,
                state JSONB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent() + "\n"

        @JvmStatic fun ensure(dataSource: HikariDataSource, logger: Logger) {
            try {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(CREATE_BLOCK_GRADIENT_SHARES_SQL)
                    }
                }
            } catch (exception: SQLException) {
                logger.error("Failed to ensure web-tool database schema", exception)
            }
        }
    }
}
