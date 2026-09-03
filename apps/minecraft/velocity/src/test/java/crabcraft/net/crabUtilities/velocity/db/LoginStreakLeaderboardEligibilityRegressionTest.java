package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class LoginStreakLeaderboardEligibilityRegressionTest {

    public static void main(String[] args) {
        CapturingDataSource dataSource = new CapturingDataSource();
        Logger logger = proxy(Logger.class,
                (proxy, method, methodArgs) -> defaultValue(method.getReturnType()));
        LoginStreakService service = new LoginStreakService(dataSource, logger, 6, 600);

        service.getLeaderboard(100, 0, false);
        service.getLeaderboard(100, 0, true);

        check(dataSource.leaderboardSql.size() == 4,
                "expected count and list queries for both streak leaderboards");
        check(dataSource.leaderboardSql.stream()
                        .allMatch(sql -> sql.contains("is_discord_member")),
                "a streak leaderboard query does not exclude departed Discord members");
        check(dataSource.leaderboardSql.stream()
                        .allMatch(sql -> sql.contains("last_mc_login_at")),
                "a streak leaderboard query does not exclude inactive players");
        check(dataSource.leaderboardSql.stream()
                        .allMatch(sql -> sql.contains("2592000")),
                "a streak leaderboard query does not use the 30-day window");

        long now = 5_000_000L;
        check(PostgresStatsWriter.isInactiveForLeaderboard(null, now),
                "a player with no recorded login should be inactive");
        check(PostgresStatsWriter.isInactiveForLeaderboard(now - 2_592_001L, now),
                "a player beyond 30 days should be inactive");
        check(!PostgresStatsWriter.isInactiveForLeaderboard(now - 2_592_000L, now),
                "a player exactly on the 30-day boundary should remain active");
        check(!PostgresStatsWriter.isInactiveForLeaderboard(now, now),
                "a player who just logged in should be active");
    }

    private static final class CapturingDataSource extends HikariDataSource {
        private final List<String> leaderboardSql = new ArrayList<>();

        @Override
        public Connection getConnection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "prepareStatement" -> preparedStatement((String) args[0]);
                default -> defaultValue(method.getReturnType());
            });
        }

        private Statement statement() {
            return proxy(Statement.class,
                    (proxy, method, args) -> defaultValue(method.getReturnType()));
        }

        private PreparedStatement preparedStatement(String rawSql) {
            String sql = rawSql.toLowerCase().replaceAll("\\s+", " ").trim();
            if (sql.contains("from player_login_streaks s")) {
                leaderboardSql.add(sql);
            }
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "executeQuery" -> resultSet();
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet() {
            return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> false;
                default -> defaultValue(method.getReturnType());
            });
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
