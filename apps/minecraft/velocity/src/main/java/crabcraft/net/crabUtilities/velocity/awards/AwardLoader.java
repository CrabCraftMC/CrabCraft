package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every enabled award definition from the Postgres {@code awards}
 * table at plugin start. The table is seeded by
 * {@code packages/db/scripts/seed-awards.ts} and is runtime-editable
 * via Drizzle Studio / SQL / any future admin UI.
 *
 * Reader specs are stored as JSONB columns and parsed back into the
 * {@link AwardDefinition.Reader} shape by Gson.
 */
public final class AwardLoader {

    private static final String SELECT_SQL = """
        SELECT id, reader_type, reader_path, reader_patterns
        FROM awards
        WHERE enabled = true
        """;

    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    private AwardLoader() {}

    public static Map<String, AwardDefinition> loadAll(HikariDataSource dataSource, Logger logger) {
        Map<String, AwardDefinition> out = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(SELECT_SQL);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                String readerType = rs.getString("reader_type");
                String readerPathJson = rs.getString("reader_path");
                String readerPatternsJson = rs.getString("reader_patterns");

                if (id == null || readerType == null || readerPathJson == null) {
                    logger.warn("Skipping award with missing required columns: id={}", id);
                    continue;
                }

                AwardDefinition def = new AwardDefinition();
                def.id = id;
                def.reader = new AwardDefinition.Reader();
                def.reader.type = readerType;
                try {
                    def.reader.path = GSON.fromJson(readerPathJson, STRING_LIST_TYPE);
                    def.reader.patterns = readerPatternsJson == null
                            ? null
                            : GSON.fromJson(readerPatternsJson, STRING_LIST_TYPE);
                } catch (Exception e) {
                    logger.warn("Skipping award {} with malformed reader spec", id, e);
                    continue;
                }
                out.put(id, def);
            }
        } catch (SQLException e) {
            logger.error("Failed to load award definitions from database", e);
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(out);
    }
}
