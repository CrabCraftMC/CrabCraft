package crabcraft.net.crabUtilities.velocity.advancements;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class AdvancementLeaderboardRegressionTest {

    public static void main(String[] args) {
        CapturingDataSource dataSource = new CapturingDataSource();
        Logger logger = proxy(Logger.class,
                (proxy, method, methodArgs) -> defaultValue(method.getReturnType()));
        AdvancementRegistry registry = new AdvancementRegistry(logger);
        AdvancementQueryService queries = new AdvancementQueryService(
                dataSource, logger, registry);

        queries.getAdvancementLeaderboard("7", 100, 0, null);

        check(dataSource.sql.size() == 2, "expected both leaderboard queries to run");
        check(dataSource.sql.stream().allMatch(sql -> sql.contains("= any (?)")),
                "a leaderboard query does not filter against the advancement registry");
        check(dataSource.sql.stream().allMatch(sql -> sql.contains("is_discord_member")),
                "a leaderboard query does not exclude departed Discord members");
        check(dataSource.sql.stream().allMatch(sql -> sql.contains("last_mc_login_at")),
                "a leaderboard query does not exclude inactive players");
        check(dataSource.sql.stream().allMatch(sql -> sql.contains("2592000")),
                "a leaderboard query does not use the 30-day window");
        check(dataSource.boundAdvancementIds.size() == 2,
                "the registry IDs were not bound to both leaderboard queries");
        check(dataSource.boundAdvancementIds.stream().allMatch(ids -> ids.size() == 126),
                "the full leaderboard did not bind all 126 registered advancements");
        check(dataSource.boundAdvancementIds.stream()
                        .noneMatch(ids -> ids.contains("custom:extra_advancement")),
                "an unregistered advancement could contribute to the leaderboard");

        dataSource.clear();
        queries.getAdvancementLeaderboard("7", 100, 0, "adventure");

        check(dataSource.boundAdvancementIds.stream().allMatch(ids -> ids.size() == 47),
                "the Adventure leaderboard did not bind its 47 registered advancements");
        check(dataSource.boundAdvancementIds.stream().flatMap(List::stream)
                        .allMatch(id -> id.startsWith("minecraft:adventure/")),
                "the Adventure leaderboard bound an advancement from another category");
    }

    private static final class CapturingDataSource extends HikariDataSource {
        private final List<String> sql = new ArrayList<>();
        private final List<List<String>> boundAdvancementIds = new ArrayList<>();

        @Override
        public Connection getConnection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "createArrayOf" -> sqlArray((Object[]) args[1]);
                case "prepareStatement" -> preparedStatement((String) args[0]);
                default -> defaultValue(method.getReturnType());
            });
        }

        private Array sqlArray(Object[] values) {
            List<String> ids = Arrays.stream(values).map(String::valueOf).toList();
            return proxy(Array.class, (proxy, method, args) -> switch (method.getName()) {
                case "getArray" -> values;
                case "toString" -> ids.toString();
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement preparedStatement(String rawSql) {
            String normalized = rawSql.toLowerCase().replaceAll("\\s+", " ").trim();
            sql.add(normalized);
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setArray" -> {
                    Object[] values = (Object[]) ((Array) args[1]).getArray();
                    boundAdvancementIds.add(
                            Arrays.stream(values).map(String::valueOf).toList());
                    yield null;
                }
                case "executeQuery" -> resultSet(normalized.startsWith("select count"));
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet(boolean countQuery) {
            boolean[] unread = {countQuery};
            return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    boolean hasNext = unread[0];
                    unread[0] = false;
                    yield hasNext;
                }
                case "getInt" -> 1;
                default -> defaultValue(method.getReturnType());
            });
        }

        private void clear() {
            sql.clear();
            boundAdvancementIds.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        throw new AssertionError("Unhandled primitive " + type);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
