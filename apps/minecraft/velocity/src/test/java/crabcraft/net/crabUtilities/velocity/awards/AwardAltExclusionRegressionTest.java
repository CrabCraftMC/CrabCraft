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
import java.util.UUID;

final class AwardAltExclusionRegressionTest {

    public static void main(String[] args) {
        CapturingDataSource dataSource = new CapturingDataSource();
        Logger logger = proxy(Logger.class,
                (proxy, method, methodArgs) -> defaultValue(method.getReturnType()));
        AwardQueryService queries = new AwardQueryService(dataSource, logger);

        JsonObject overview = queries.getAllAwards("7");
        JsonObject awardLeaderboard = queries.getAwardLeaderboard("test-award", "7", 10, 0);
        queries.getCrownLeaderboard("7", 10, 0);
        queries.getPlayerAwards("primary-uuid", "7");

        JsonObject visibleLeader = overview.getAsJsonArray("awards").get(0)
                .getAsJsonObject().getAsJsonObject("leader");
        check(!visibleLeader.get("hidden").getAsBoolean()
                        && visibleLeader.get("uuid").getAsString().equals(dataSource.visibleUuid),
                "default awards overview lost its visible leader");

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
        check(rankingQueries.stream().allMatch(sql -> sql.contains("eligible_player.awards_excluded = false")),
                "a public award query can expose a moderator-excluded player");
        check(rankingQueries.stream().allMatch(sql -> sql.contains("last_mc_login_at")),
                "a public award query does not exclude inactive players");
        check(rankingQueries.stream().allMatch(sql -> sql.contains("2592000")),
                "a public award query does not use the 30-day window");
        check(rankingQueries.stream().filter(sql -> sql.contains("rank() over")).count() == 5,
                "medal-bearing award queries do not derive ranks after filtering alts");

        check(dataSource.visibility.stream().allMatch(value -> !value),
                "a leaderboard includes hidden players by default");
        for (JsonObject response : List.of(
                queries.getAllAwards("7", true),
                queries.getAwardLeaderboard("test-award", "7", 10, 0, true),
                queries.getCrownLeaderboard("7", 10, 0, true))) {
            JsonObject hidden = response.has("awards")
                    ? response.getAsJsonArray("awards").get(0).getAsJsonObject().getAsJsonObject("leader")
                    : response.getAsJsonArray("leaderboard").get(0).getAsJsonObject();
            check(hidden.get("hidden").getAsBoolean(), "hidden entry lost its marker");
            check(hidden.get("uuid").isJsonNull() && hidden.get("nickname").isJsonNull(),
                    "hidden entry exposes a UUID or nickname");
            check(hidden.get("username").getAsString().equals("Hidden player"),
                    "hidden entry exposes its username");
            check(!response.toString().contains(dataSource.hiddenUuid)
                            && !response.toString().contains(dataSource.hiddenName),
                    "hidden identity leaked in the API payload");
        }

        dataSource.sql.clear();
        new AwardDbWriter(dataSource, logger).recomputeMedals("7");

        check(dataSource.sql.size() == 2, "medal recomputation must reset then rank");
        check(dataSource.sql.get(0).contains("set medal = 0"),
                "stale alt medals are not cleared");
        check(dataSource.sql.get(1).contains("player_alts"),
                "alt accounts can still consume medal positions");
        check(dataSource.sql.get(1).contains("is_discord_member"),
                "departed Discord members can still consume medal positions");
        check(dataSource.sql.get(1).contains("eligible_player.awards_excluded = false"),
                "moderator-excluded players can still consume medal positions");
        check(dataSource.sql.get(1).contains("last_mc_login_at"),
                "inactive players can still consume medal positions");

        dataSource.sql.clear();
        new AwardDbWriter(dataSource, logger).recomputeAllMedals();

        check(dataSource.sql.size() == 3,
                "all-season medal recomputation must load, reset and rank each season");
        check(dataSource.sql.get(2).contains("last_mc_login_at"),
                "reactivating a player does not apply the inactivity cutoff");
        check(dataSource.sql.get(2).contains("eligible_player.awards_excluded = false"),
                "reactivating a player bypasses their moderator exclusion");
    }

    private static final class CapturingDataSource extends HikariDataSource {
        private final List<String> sql = new ArrayList<>();
        private final List<Boolean> visibility = new ArrayList<>();
        private final String hiddenUuid = UUID.randomUUID().toString();
        private final String hiddenName = "Fixture_" + UUID.randomUUID();
        private final String visibleUuid = UUID.randomUUID().toString();
        private final String visibleName = "Fixture_" + UUID.randomUUID();

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
            boolean[] showHidden = {false};
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setBoolean" -> {
                    showHidden[0] = (boolean) args[1];
                    visibility.add(showHidden[0]);
                    yield null;
                }
                case "executeQuery" -> resultSet(normalized, showHidden[0]);
                case "executeUpdate" -> 0;
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet(String sql, boolean showHidden) {
            List<Map<String, Object>> rows;
            if (sql.contains("select distinct on (scores.award_id)")) {
                rows = List.of(Map.of(
                        "award_id", "test-award",
                        "minecraft_uuid", showHidden ? hiddenUuid : visibleUuid,
                        "minecraft_username", showHidden ? hiddenName : visibleName,
                        "nickname", "Alias_" + (showHidden ? hiddenName : visibleName),
                        "hidden", showHidden,
                        "best_score", new java.util.Random().nextDouble(1000, 2000)));
            } else if (showHidden && (sql.contains("case when ranked.rnk <= 3") || sql.contains("crowns.gold,"))) {
                rows = List.of(Map.ofEntries(
                        Map.entry("minecraft_uuid", hiddenUuid), Map.entry("minecraft_username", hiddenName),
                        Map.entry("nickname", "Alias_" + hiddenName), Map.entry("hidden", true),
                        Map.entry("score", new java.util.Random().nextDouble(1000, 2000)),
                        Map.entry("rnk", 1), Map.entry("medal", 1), Map.entry("gold", 2),
                        Map.entry("silver", 1), Map.entry("bronze", 0), Map.entry("crown_score", 13)));
            } else if (sql.contains("select distinct season from player_award_scores")) {
                rows = List.of(Map.of("season", "7"));
            } else if (sql.contains("from awards where id = ?") || sql.contains("from awards where enabled = true")) {
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
                case "getString" -> (String) rows.get(index[0]).get(args[0]);
                case "getBoolean" -> Boolean.TRUE.equals(rows.get(index[0]).get(args[0]));
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
