package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * One-shot seeder that populates the {@code awards} table from the
 * bundled JSON when the table is empty.
 *
 * <p>Only runs when the table has zero rows — so admin-disabled awards,
 * custom awards, and hand-edits all survive restarts. Safe to call on
 * every plugin start.
 *
 * <p>The JSON source lives at
 * {@code packages/db/seeds/awards.json} in the repo and is copied into
 * the plugin JAR at {@code /crabcraft/awards.json} by the velocity
 * {@code build.gradle}.
 */
public final class AwardSeeder {

    private static final String RESOURCE = "/crabcraft/awards.json";
    private static final String COUNT_SQL = "SELECT COUNT(*) FROM awards";

    private static final String INSERT_SQL = """
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
        """;

    private static final Gson GSON = new Gson();

    private AwardSeeder() {}

    public static void seedIfEmpty(HikariDataSource dataSource, Logger logger) {
        long existing;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement count = conn.prepareStatement(COUNT_SQL);
             ResultSet rs = count.executeQuery()) {
            rs.next();
            existing = rs.getLong(1);
        } catch (SQLException e) {
            logger.error("Award seed: count probe failed", e);
            return;
        }
        if (existing > 0) return;

        JsonArray rows;
        try (InputStream in = AwardSeeder.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                logger.warn("Award seed: resource {} missing from plugin JAR", RESOURCE);
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                rows = GSON.fromJson(reader, JsonArray.class);
            }
        } catch (Exception e) {
            logger.error("Award seed: failed to read bundled JSON", e);
            return;
        }
        if (rows == null || rows.size() == 0) return;

        int seeded = 0;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            conn.setAutoCommit(false);
            try {
                for (int i = 0; i < rows.size(); i++) {
                    JsonObject r = rows.get(i).getAsJsonObject();
                    JsonObject reader = r.getAsJsonObject("reader");
                    stmt.setString(1, r.get("id").getAsString());
                    stmt.setString(2, r.get("title").getAsString());
                    stmt.setString(3, r.get("description").getAsString());
                    stmt.setString(4, r.get("unit").getAsString());
                    stmt.setString(5, r.get("bucket").getAsString());
                    stmt.setString(6, r.get("icon").getAsString());
                    stmt.setString(7, reader.get("type").getAsString());
                    stmt.setString(8, reader.getAsJsonArray("path").toString());
                    JsonElement patterns = reader.get("patterns");
                    stmt.setString(9, patterns == null ? null : patterns.getAsJsonArray().toString());
                    stmt.setInt(10, i);
                    stmt.addBatch();
                    seeded++;
                }
                stmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Award seed: insert failed", e);
            return;
        }

        logger.info("Seeded {} awards into empty table", seeded);
    }
}
