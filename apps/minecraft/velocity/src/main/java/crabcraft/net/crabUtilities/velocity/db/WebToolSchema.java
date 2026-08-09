package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Keeps web-tool tables in lockstep with {@code packages/db/src/schema.ts}.
 * Velocity does not query these rows; it creates the schema so either runtime
 * can initialise a fresh database without introducing Drizzle drift.
 */
public final class WebToolSchema {

    private static final String CREATE_BLOCK_GRADIENT_SHARES_SQL = """
            CREATE TABLE IF NOT EXISTS block_gradient_shares (
                id TEXT PRIMARY KEY,
                version INTEGER NOT NULL DEFAULT 1,
                state JSONB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """;

    private WebToolSchema() {
    }

    public static void ensure(HikariDataSource dataSource, Logger logger) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_BLOCK_GRADIENT_SHARES_SQL);
        } catch (SQLException exception) {
            logger.error("Failed to ensure web-tool database schema", exception);
        }
    }
}
