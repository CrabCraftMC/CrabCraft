package crabcraft.net.crabUtilities.velocity.advancements;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class AdvancementDbWriter {

    private static final String AGGREGATE_SERVER_ID = "__aggregate__";

    private static final String UPSERT_ADVANCEMENT = """
        INSERT INTO player_advancements
            (minecraft_uuid, season, server_id, advancement_id, completed, completed_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (minecraft_uuid, season, server_id, advancement_id) DO UPDATE SET
            completed = EXCLUDED.completed,
            completed_at = COALESCE(EXCLUDED.completed_at, player_advancements.completed_at)
        """;

    private static final String RECOMPUTE_AGGREGATE = """
        INSERT INTO player_advancements
            (minecraft_uuid, season, server_id, advancement_id, completed, completed_at)
        SELECT
            minecraft_uuid, season, ?::text, advancement_id,
            BOOL_OR(completed),
            MIN(completed_at) FILTER (WHERE completed_at IS NOT NULL)
        FROM player_advancements
        WHERE minecraft_uuid = ?
          AND season = ?
          AND server_id <> ?
        GROUP BY minecraft_uuid, season, advancement_id
        ON CONFLICT (minecraft_uuid, season, server_id, advancement_id) DO UPDATE SET
            completed = EXCLUDED.completed,
            completed_at = COALESCE(EXCLUDED.completed_at, player_advancements.completed_at)
        """;

    private static final DateTimeFormatter MC_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    private final HikariDataSource dataSource;
    private final Logger logger;

    public AdvancementDbWriter(HikariDataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    public void writeForPlayerOnServer(String uuid, String season,
                                        String serverId, JsonObject advancements) {
        if (advancements == null || advancements.size() == 0) return;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                upsertAdvancements(conn, uuid, season, serverId, advancements);
                recomputeAggregate(conn, uuid, season);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to write advancements for uuid={} server={}", uuid, serverId, e);
        }
    }

    private void upsertAdvancements(Connection conn, String uuid, String season,
                                     String serverId, JsonObject advancements) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UPSERT_ADVANCEMENT)) {
            for (Map.Entry<String, JsonElement> entry : advancements.entrySet()) {
                String advId = entry.getKey();
                if (advId.equals("DataVersion") || !entry.getValue().isJsonObject()) continue;

                JsonObject adv = entry.getValue().getAsJsonObject();
                boolean done = adv.has("done") && adv.get("done").getAsBoolean();
                Integer completedAt = parseCompletedAt(adv);

                stmt.setString(1, uuid);
                stmt.setString(2, season);
                stmt.setString(3, serverId);
                stmt.setString(4, advId);
                stmt.setBoolean(5, done);
                if (completedAt != null) {
                    stmt.setInt(6, completedAt);
                } else {
                    stmt.setNull(6, java.sql.Types.INTEGER);
                }
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void recomputeAggregate(Connection conn, String uuid, String season) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(RECOMPUTE_AGGREGATE)) {
            stmt.setString(1, AGGREGATE_SERVER_ID);
            stmt.setString(2, uuid);
            stmt.setString(3, season);
            stmt.setString(4, AGGREGATE_SERVER_ID);
            stmt.executeUpdate();
        }
    }

    private static Integer parseCompletedAt(JsonObject adv) {
        if (!adv.has("criteria") || !adv.get("criteria").isJsonObject()) return null;
        JsonObject criteria = adv.getAsJsonObject("criteria");
        long earliest = Long.MAX_VALUE;
        for (Map.Entry<String, JsonElement> c : criteria.entrySet()) {
            if (!c.getValue().isJsonPrimitive()) continue;
            try {
                LocalDateTime dt = LocalDateTime.parse(c.getValue().getAsString(), MC_DATE_FORMAT);
                long epoch = dt.toInstant(ZoneOffset.UTC).getEpochSecond();
                if (epoch < earliest) earliest = epoch;
            } catch (Exception ignored) {}
        }
        return earliest == Long.MAX_VALUE ? null : (int) earliest;
    }
}
