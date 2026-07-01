package crabcraft.net.crabUtilities.velocity.litebans;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public-history adapter for LiteBans. Uses LiteBans' Database API reflectively
 * so CrabUtilities still loads when LiteBans is not installed.
 */
public final class LiteBansInfractionService {

    private static final int MAX_LIMIT = 25;
    private static final Source[] SOURCES = {
            new Source("ban", "bans"),
            new Source("mute", "mutes"),
            new Source("warning", "warnings"),
            new Source("kick", "kicks"),
    };
    private static final Source[] ACTIVE_PUNISHMENT_SOURCES = {
            new Source("ban", "bans"),
            new Source("mute", "mutes"),
    };
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final Logger logger;
    private volatile Method databaseGetMethod;
    private volatile Method prepareStatementMethod;
    private volatile boolean unavailableLogged;

    public LiteBansInfractionService(Logger logger) {
        this.logger = logger;
    }

    public boolean isAvailable() {
        try {
            return getDatabase() != null;
        } catch (LiteBansUnavailableException e) {
            return false;
        }
    }

    public JsonObject getInfractionsJson(String uuid, int requestedLimit)
            throws LiteBansUnavailableException, SQLException {
        int limit = Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
        String normalizedUuid = UUID.fromString(uuid).toString();
        List<Infraction> infractions = new ArrayList<>();
        String compactUuid = normalizedUuid.replace("-", "");

        Object database = getDatabase();
        for (Source source : SOURCES) {
            infractions.addAll(querySource(database, source, normalizedUuid, compactUuid, limit));
        }
        infractions.sort((a, b) -> Long.compare(b.createdAtMs(), a.createdAtMs()));

        JsonArray out = new JsonArray();
        int count = Math.min(limit, infractions.size());
        for (int i = 0; i < count; i++) {
            out.add(toJson(infractions.get(i)));
        }

        JsonObject response = new JsonObject();
        response.addProperty("uuid", normalizedUuid);
        response.addProperty("count", count);
        response.add("infractions", out);
        return response;
    }

    public JsonObject getActivePunishmentsJson(Collection<String> normalizedUuids)
            throws LiteBansUnavailableException, SQLException {
        Set<String> punishedUuids = getActivePunishedUuids(normalizedUuids);

        JsonArray out = new JsonArray();
        for (String uuid : punishedUuids) {
            out.add(uuid);
        }

        JsonObject response = new JsonObject();
        response.addProperty("count", out.size());
        response.add("punished_uuids", out);
        return response;
    }

    public Set<String> getActivePunishedUuids(Collection<String> normalizedUuids)
            throws LiteBansUnavailableException, SQLException {
        Map<String, String> uuidLookup = buildUuidLookup(normalizedUuids);
        Set<String> punishedUuids = new LinkedHashSet<>();

        if (!uuidLookup.isEmpty()) {
            Object database = getDatabase();
            for (Source source : ACTIVE_PUNISHMENT_SOURCES) {
                punishedUuids.addAll(queryActivePunishments(database, source, uuidLookup));
            }
        }

        return punishedUuids;
    }

    public Set<String> getAllActivePunishedUuids()
            throws LiteBansUnavailableException, SQLException {
        Set<String> punishedUuids = new LinkedHashSet<>();
        Object database = getDatabase();
        for (Source source : ACTIVE_PUNISHMENT_SOURCES) {
            punishedUuids.addAll(queryAllActivePunishments(database, source));
        }
        return punishedUuids;
    }

    private List<Infraction> querySource(Object database, Source source, String uuid,
                                         String compactUuid, int limit)
            throws SQLException, LiteBansUnavailableException {
        String sql = "SELECT * FROM {" + source.tableToken() + "} "
                + "WHERE uuid = ? OR uuid = ? ORDER BY time DESC LIMIT ?";
        try (PreparedStatement stmt = prepareStatement(database, sql)) {
            try {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            } catch (SQLException ignored) {
                // Some JDBC drivers do not support per-statement timeouts.
            }
            stmt.setString(1, uuid);
            stmt.setString(2, compactUuid);
            stmt.setInt(3, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Infraction> rows = new ArrayList<>();
                while (rs.next()) {
                    Infraction infraction = readInfraction(source.type(), rs);
                    if (infraction != null) {
                        rows.add(infraction);
                    }
                }
                return rows;
            }
        }
    }

    private Set<String> queryActivePunishments(Object database, Source source,
                                               Map<String, String> uuidLookup)
            throws SQLException, LiteBansUnavailableException {
        long nowEpochSeconds = System.currentTimeMillis() / 1000L;
        String sql = "SELECT uuid, until, removed_by_name, removed_by_date FROM {" + source.tableToken() + "} "
                + "WHERE active = 1 AND uuid IN (" + placeholders(uuidLookup.size()) + ")";
        try (PreparedStatement stmt = prepareStatement(database, sql)) {
            try {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            } catch (SQLException ignored) {
                // Some JDBC drivers do not support per-statement timeouts.
            }
            int index = 1;
            for (String uuid : uuidLookup.keySet()) {
                stmt.setString(index++, uuid);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, Integer> columns = columns(rs.getMetaData());
                Set<String> rows = new LinkedHashSet<>();
                while (rs.next()) {
                    if (!isCurrentPunishment(columns, rs, nowEpochSeconds)) continue;
                    String rawUuid = rs.getString("uuid");
                    if (rawUuid == null) continue;
                    String normalizedUuid = uuidLookup.get(rawUuid.toLowerCase(Locale.ROOT));
                    if (normalizedUuid != null) {
                        rows.add(normalizedUuid);
                    }
                }
                return rows;
            }
        }
    }

    private Set<String> queryAllActivePunishments(Object database, Source source)
            throws SQLException, LiteBansUnavailableException {
        long nowEpochSeconds = System.currentTimeMillis() / 1000L;
        String sql = "SELECT uuid, until, removed_by_name, removed_by_date FROM {" + source.tableToken() + "} WHERE active = 1";
        try (PreparedStatement stmt = prepareStatement(database, sql)) {
            try {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            } catch (SQLException ignored) {
                // Some JDBC drivers do not support per-statement timeouts.
            }
            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, Integer> columns = columns(rs.getMetaData());
                Set<String> rows = new LinkedHashSet<>();
                while (rs.next()) {
                    if (!isCurrentPunishment(columns, rs, nowEpochSeconds)) continue;
                    String normalizedUuid = normalizeStoredUuid(rs.getString("uuid"));
                    if (normalizedUuid != null) {
                        rows.add(normalizedUuid);
                    }
                }
                return rows;
            }
        }
    }

    private static Map<String, String> buildUuidLookup(Collection<String> normalizedUuids) {
        Map<String, String> lookup = new LinkedHashMap<>();
        for (String uuid : normalizedUuids) {
            String normalized = UUID.fromString(uuid).toString();
            lookup.put(normalized, normalized);
            lookup.put(normalized.replace("-", ""), normalized);
        }
        return lookup;
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) builder.append(',');
            builder.append('?');
        }
        return builder.toString();
    }

    private static String normalizeStoredUuid(String uuid) {
        if (uuid == null) return null;
        String value = uuid.trim();
        try {
            if (value.length() == 32) {
                value = value.substring(0, 8) + "-"
                        + value.substring(8, 12) + "-"
                        + value.substring(12, 16) + "-"
                        + value.substring(16, 20) + "-"
                        + value.substring(20);
            }
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isCurrentPunishment(Map<String, Integer> columns,
                                               ResultSet rs,
                                               long nowEpochSeconds) throws SQLException {
        String removedBy = getString(columns, rs, "removed_by_name");
        Long removedAt = getNullableTimestampMillis(columns, rs, "removed_by_date");
        if ((removedBy != null && !removedBy.isBlank()) || (removedAt != null && removedAt > 0L)) {
            return false;
        }

        Long expiresAt = getNullableTimestampMillis(columns, rs, "until");
        return expiresAt == null || expiresAt <= 0L || toEpochSeconds(expiresAt) > nowEpochSeconds;
    }

    private Infraction readInfraction(String type, ResultSet rs) throws SQLException {
        Map<String, Integer> columns = columns(rs.getMetaData());

        Long createdAtValue = getNullableTimestampMillis(columns, rs, "time");
        long createdAtMs = createdAtValue == null ? 0L : createdAtValue;
        Long expiresAtMs = getNullableTimestampMillis(columns, rs, "until");
        if (expiresAtMs != null && expiresAtMs <= 0L) {
            expiresAtMs = null;
        }
        Boolean active = getNullableBoolean(columns, rs, "active");
        String removedBy = getString(columns, rs, "removed_by_name");
        Long removedAtMs = getNullableTimestampMillis(columns, rs, "removed_by_date");
        boolean removed = (removedAtMs != null && removedAtMs > 0L) || removedBy != null;

        return new Infraction(
                type,
                getLong(columns, rs, "id", 0L),
                getString(columns, rs, "reason"),
                getString(columns, rs, "banned_by_name"),
                createdAtMs,
                expiresAtMs,
                active,
                removed,
                removedBy,
                removedAtMs);
    }

    private PreparedStatement prepareStatement(Object database, String sql)
            throws LiteBansUnavailableException, SQLException {
        try {
            Method method = prepareStatementMethod;
            if (method == null) {
                method = database.getClass().getMethod("prepareStatement", String.class);
                prepareStatementMethod = method;
            }
            return (PreparedStatement) method.invoke(database, sql);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            Throwable cause = e instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : e;
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new LiteBansUnavailableException(cause);
        }
    }

    private Object getDatabase() throws LiteBansUnavailableException {
        try {
            Method method = databaseGetMethod;
            if (method == null) {
                Class<?> databaseClass = Class.forName("litebans.api.Database");
                method = databaseClass.getMethod("get");
                databaseGetMethod = method;
            }
            Object database = method.invoke(null);
            if (database == null) {
                throw new LiteBansUnavailableException("LiteBans database is not ready");
            }
            unavailableLogged = false;
            return database;
        } catch (ClassNotFoundException e) {
            logUnavailableOnce("LiteBans API is not present; infractions endpoint will return 503");
            throw new LiteBansUnavailableException(e);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            Throwable cause = e instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : e;
            logUnavailableOnce("LiteBans API is not available; infractions endpoint will return 503");
            throw new LiteBansUnavailableException(cause);
        }
    }

    private void logUnavailableOnce(String message) {
        if (!unavailableLogged) {
            logger.info(message);
            unavailableLogged = true;
        }
    }

    private static JsonObject toJson(Infraction infraction) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", infraction.type());
        obj.addProperty("id", infraction.id());
        addNullable(obj, "reason", infraction.reason());
        addNullable(obj, "staff", infraction.staff());
        obj.addProperty("created_at", toEpochSeconds(infraction.createdAtMs()));
        addNullableEpoch(obj, "expires_at", infraction.expiresAtMs());
        if (infraction.active() == null) {
            obj.add("active", JsonNull.INSTANCE);
        } else {
            obj.addProperty("active", infraction.active());
        }
        obj.addProperty("removed", infraction.removed());
        addNullable(obj, "removed_by", infraction.removedBy());
        addNullableEpoch(obj, "removed_at", infraction.removedAtMs());
        return obj;
    }

    private static void addNullable(JsonObject obj, String key, String value) {
        if (value == null || value.isBlank()) {
            obj.add(key, JsonNull.INSTANCE);
        } else {
            obj.addProperty(key, value);
        }
    }

    private static void addNullableEpoch(JsonObject obj, String key, Long value) {
        if (value == null || value <= 0L) {
            obj.add(key, JsonNull.INSTANCE);
        } else {
            obj.addProperty(key, toEpochSeconds(value));
        }
    }

    private static long toEpochSeconds(long value) {
        return value > 10_000_000_000L ? value / 1000L : value;
    }

    private static Map<String, Integer> columns(ResultSetMetaData meta) throws SQLException {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
        }
        return columns;
    }

    private static String getString(Map<String, Integer> columns, ResultSet rs, String column)
            throws SQLException {
        Integer index = columns.get(column);
        if (index == null) return null;
        String value = rs.getString(index);
        return rs.wasNull() ? null : value;
    }

    private static long getLong(Map<String, Integer> columns, ResultSet rs, String column,
                                long fallback) throws SQLException {
        Long value = getNullableLong(columns, rs, column);
        return value == null ? fallback : value;
    }

    private static Long getNullableLong(Map<String, Integer> columns, ResultSet rs, String column)
            throws SQLException {
        Integer index = columns.get(column);
        if (index == null) return null;
        long value = rs.getLong(index);
        return rs.wasNull() ? null : value;
    }

    private static Long getNullableTimestampMillis(Map<String, Integer> columns, ResultSet rs,
                                                   String column) throws SQLException {
        Integer index = columns.get(column);
        if (index == null) return null;
        Object value = rs.getObject(index);
        if (value == null || rs.wasNull()) return null;
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof java.util.Date date) {
            return date.getTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant().toEpochMilli();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant().toEpochMilli();
        }

        String text = value.toString().trim();
        if (text.isEmpty()) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // H2 LiteBans date fields can be formatted as yyyy-MM-dd HH:mm:ss.SSS.
        }
        try {
            return Timestamp.valueOf(text).getTime();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Boolean getNullableBoolean(Map<String, Integer> columns, ResultSet rs,
                                              String column) throws SQLException {
        Integer index = columns.get(column);
        if (index == null) return null;
        boolean value = rs.getBoolean(index);
        return rs.wasNull() ? null : value;
    }

    private record Source(String type, String tableToken) {}

    private record Infraction(
            String type,
            long id,
            String reason,
            String staff,
            long createdAtMs,
            Long expiresAtMs,
            Boolean active,
            boolean removed,
            String removedBy,
            Long removedAtMs) {}

    public static final class LiteBansUnavailableException extends Exception {
        public LiteBansUnavailableException(String message) {
            super(message);
        }

        public LiteBansUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
