package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AwardAltExclusionRegressionTest {

    public static void main(String[] args) {
        CapturingDataSource dataSource = new CapturingDataSource();
        Logger logger = proxy(Logger.class,
                (proxy, method, methodArgs) -> defaultValue(method.getReturnType()));
        AwardQueryService queries = new AwardQueryService(dataSource, logger);

        queries.getAllAwards("7");
        JsonObject awardLeaderboard = queries.getAwardLeaderboard("test-award", "7", 10, 0);
        queries.getCrownLeaderboard("7", 10, 0);
        queries.getPlayerAwards("primary-uuid", "7");

        JsonArray entries = awardLeaderboard.getAsJsonArray("leaderboard");
        check(entries.size() == 3, "expected the tie-ranking fixture");
        check(entries.get(0).getAsJsonObject().get("rank").getAsInt() == 1,
                "first tied player did not receive rank 1");
        check(entries.get(1).getAsJsonObject().get("rank").getAsInt() == 1,
                "second tied player did not receive rank 1");
        check(entries.get(2).getAsJsonObject().get("rank").getAsInt() == 3,
                "rank after a two-way tie should skip to 3");

        List<String> rankingQueries = dataSource.sql.stream()
                .filter(sql -> sql.contains("player_award_scores"))
                .toList();
        check(rankingQueries.size() == 7, "expected every public award query to be exercised");
        check(rankingQueries.stream().allMatch(sql -> sql.contains("player_alts")),
                "a public award query does not exclude alt accounts");
        check(rankingQueries.stream().allMatch(sql -> sql.contains("is_discord_member")),
                "a public award query does not exclude departed Discord members");
        check(rankingQueries.stream().allMatch(sql -> sql.contains("last_mc_login_at")),
                "a public award query does not exclude inactive players");
        check(rankingQueries.stream().allMatch(sql -> sql.contains("2592000")),
                "a public award query does not use the 30-day window");
        check(rankingQueries.stream().filter(sql -> sql.contains("rank() over")).count() == 5,
                "medal-bearing award queries do not derive ranks after filtering alts");

        dataSource.sql.clear();
        new AwardDbWriter(dataSource, logger).recomputeMedals("7");

        check(dataSource.sql.size() == 2, "medal recomputation must reset then rank");
        check(dataSource.sql.get(0).contains("set medal = 0"),
                "stale alt medals are not cleared");
        check(dataSource.sql.get(1).contains("player_alts"),
                "alt accounts can still consume medal positions");
        check(dataSource.sql.get(1).contains("is_discord_member"),
                "departed Discord members can still consume medal positions");
        check(dataSource.sql.get(1).contains("last_mc_login_at"),
                "inactive players can still consume medal positions");

        dataSource.sql.clear();
        new AwardDbWriter(dataSource, logger).recomputeAllMedals();

        check(dataSource.sql.size() == 3,
                "all-season medal recomputation must load, reset and rank each season");
        check(dataSource.sql.get(2).contains("last_mc_login_at"),
                "reactivating a player does not apply the inactivity cutoff");
    }

    private static final class CapturingDataSource extends HikariDataSource {
        private final List<String> sql = new ArrayList<>();

        @Override
        public Connection getConnection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> preparedStatement((String) args[0]);
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement preparedStatement(String rawSql) {
            String normalized = rawSql.toLowerCase().replaceAll("\\s+", " ").trim();
            sql.add(normalized);
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "executeQuery" -> resultSet(normalized);
                case "executeUpdate" -> 0;
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet(String sql) {
            List<Map<String, Object>> rows;
            if (sql.contains("select distinct season from player_award_scores")) {
                rows = List.of(Map.of("season", "7"));
            } else if (sql.contains("from awards where id = ?")) {
                rows = List.of(Map.of(
                        "id", "test-award",
                        "title", "Test Award",
                        "description", "Test",
                        "unit", "int",
                        "bucket", "misc",
                        "icon", "test.png"));
            } else if (sql.contains("case when ranked.rnk <= 3")) {
                rows = List.of(
                        Map.of(
                                "minecraft_uuid", "primary-uuid",
                                "minecraft_username", "Primary",
                                "score", 42d,
                                "rnk", 1,
                                "medal", 1),
                        Map.of(
                                "minecraft_uuid", "second-uuid",
                                "minecraft_username", "Second",
                                "score", 42d,
                                "rnk", 1,
                                "medal", 1),
                        Map.of(
                                "minecraft_uuid", "third-uuid",
                                "minecraft_username", "Third",
                                "score", 41d,
                                "rnk", 3,
                                "medal", 3));
            } else if (sql.contains("select award_id, score, rank from")) {
                rows = List.of(Map.of("award_id", "test-award", "score", 1d, "rank", 1));
            } else {
                rows = List.of();
            }
            int[] index = {-1};
            return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++index[0] < rows.size();
                case "getString" -> String.valueOf(rows.get(index[0]).get(args[0]));
                case "getInt" -> ((Number) rows.get(index[0]).get(args[0])).intValue();
                case "getDouble" -> ((Number) rows.get(index[0]).get(args[0])).doubleValue();
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
