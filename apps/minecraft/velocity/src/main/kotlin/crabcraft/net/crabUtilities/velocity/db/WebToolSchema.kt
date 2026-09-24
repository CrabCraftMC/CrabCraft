package crabcraft.net.crabUtilities.velocity.db

import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException
import java.sql.Statement
import org.slf4j.Logger

/** Initialises web-tool tables shared with Drizzle so either runtime can create a fresh database. */
class WebToolSchema {
    private constructor() {}

    companion object {
        private val CREATE_BLOCK_GRADIENT_SHARES_SQL: String =
            ("""
            CREATE TABLE IF NOT EXISTS block_gradient_shares (
                id TEXT PRIMARY KEY,
                version INTEGER NOT NULL DEFAULT 1,
                state JSONB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
                .trimIndent() + "\n")

        @JvmStatic
        fun ensure(dataSource: HikariDataSource, logger: Logger) {
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
