package crabcraft.net.crabUtilities.velocity.awards;

import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import crabcraft.net.crabUtilities.awards.EatingAwardSnapshot;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Explicit integration check; creates and removes its own schema in a disposable PostgreSQL database. */
final class EatingAwardDbIntegrationTest {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("CRABCRAFT_TEST_JDBC_URL");
        if (url == null) throw new IllegalArgumentException("Set CRABCRAFT_TEST_JDBC_URL to a disposable PostgreSQL database");
        String schema = "eating_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url); var sql = admin.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setSchema(schema);
            config.setMaximumPoolSize(3);
            try (var db = new HikariDataSource(config)) {
                try (var conn = db.getConnection(); var setup = conn.createStatement()) {
                    setup.execute("CREATE TABLE awards (id TEXT PRIMARY KEY)");
                    setup.execute("INSERT INTO awards VALUES ('eat_meat'), ('play')");
                    setup.execute("""
                            CREATE TABLE player_award_scores (
                                id SERIAL PRIMARY KEY, minecraft_uuid TEXT NOT NULL, season TEXT NOT NULL,
                                award_id TEXT NOT NULL REFERENCES awards(id), score REAL NOT NULL DEFAULT 0,
                                medal INTEGER NOT NULL DEFAULT 0, computed_at INTEGER NOT NULL,
                                eating_progress JSONB, UNIQUE (minecraft_uuid, season, award_id)
                            )
                            """);
                }
                var logger = LoggerFactory.getLogger(EatingAwardDbIntegrationTest.class);
                var writer = new AwardDbWriter(db, logger);
                writer.writeScoresForPlayer("player", "7", Map.of("eat_meat", 2495d, "play", 100d));
                write(writer, new EatingAwardSnapshot(100, 200, 10, null));
                check(score(db, "player", "7", "eat_meat") == 2495d, "first checkpoint changed the displayed score");

                CountDownLatch start = new CountDownLatch(1);
                try (var workers = Executors.newFixedThreadPool(2)) {
                    var first = workers.submit(() -> {
                        start.await();
                        write(writer, new EatingAwardSnapshot(100, 300, 11, null));
                        return null;
                    });
                    var second = workers.submit(() -> {
                        start.await();
                        write(writer, new EatingAwardSnapshot(100, 400, 12, null));
                        return null;
                    });
                    start.countDown();
                    first.get();
                    second.get();
                }
                check(score(db, "player", "7", "eat_meat") == 2497d, "concurrent deliveries lost or repeated meals");
                write(writer, new EatingAwardSnapshot(100, 400, 12, null));
                write(writer, new EatingAwardSnapshot(100, 300, 11, null));
                check(score(db, "player", "7", "eat_meat") == 2497d, "replays changed the score");

                // A fresh writer/pool must recover the checkpoint from PostgreSQL, not memory.
                try (var reopened = new HikariDataSource(config)) {
                    var restarted = new AwardDbWriter(reopened, logger);
                    write(restarted, new EatingAwardSnapshot(100, 500, 13, null));
                    check(score(reopened, "player", "7", "eat_meat") == 2498d, "restart lost the checkpoint");
                    restarted.writeScoresForPlayer("player", "7", Map.of("eat_meat", 9000d, "play", 101d));
                    check(score(reopened, "player", "7", "eat_meat") == 2498d, "legacy payload overwrote tracked meals");
                    check(score(reopened, "player", "7", "play") == 101d, "ordinary awards stopped updating");
                    write(restarted, new EatingAwardSnapshot(100, 600, 13, 100L));
                    check(score(reopened, "player", "7", "eat_meat") == 113d, "historical correction was not applied");
                    write(restarted, new EatingAwardSnapshot(100, 700, 12, 100L));
                    write(restarted, new EatingAwardSnapshot(100, 800, 14, null));
                    check(score(reopened, "player", "7", "eat_meat") == 113d, "stale or pending data undid verified history");

                    restarted.writeScoresForPlayer("new-player", "7", Map.of(),
                            Map.of("eat_meat", new EatingAwardSnapshot(100, 200, 2, 0L)));
                    check(score(reopened, "new-player", "7", "eat_meat") == 2d, "new player lost meals");
                    restarted.writeScoresForPlayer("player", "8", Map.of(),
                            Map.of("eat_meat", new EatingAwardSnapshot(100, 200, 2, 0L)));
                    check(score(reopened, "player", "8", "eat_meat") == 2d, "checkpoint leaked across seasons");

                    // Force a constraint failure after a valid checkpoint update; neither write may commit.
                    restarted.writeScoresForPlayer("player", "7", Map.of("play", 999d), Map.of(
                            "eat_meat", new EatingAwardSnapshot(100, 900, 14, 100L),
                            "eat_missing", new EatingAwardSnapshot(100, 900, 0, 0L)));
                    check(score(reopened, "player", "7", "play") == 101d, "failed transaction committed ordinary scores");
                    check(score(reopened, "player", "7", "eat_meat") == 113d, "failed transaction committed eating score");
                    try (var conn = reopened.getConnection(); var stmt = conn.createStatement();
                         var rs = stmt.executeQuery("SELECT eating_progress FROM player_award_scores WHERE minecraft_uuid = 'player' AND season = '7' AND award_id = 'eat_meat'")) {
                        check(rs.next(), "checkpoint row disappeared");
                        var snapshot = EatingAwardSnapshot.fromJson(JsonParser.parseString(rs.getString(1)).getAsJsonObject());
                        check(snapshot.meals() == 13 && snapshot.capturedAt() == 600, "failed transaction committed a checkpoint");
                    }
                }
                System.out.println("Eating awards: concurrent delivery, replay, restart, correction, legacy payload and rollback checks passed.");
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void write(AwardDbWriter writer, EatingAwardSnapshot snapshot) {
        writer.writeScoresForPlayer("player", "7", Map.of(), Map.of("eat_meat", snapshot));
    }

    private static double score(HikariDataSource db, String player, String season, String award) throws Exception {
        try (var conn = db.getConnection(); var stmt = conn.prepareStatement(
                "SELECT score FROM player_award_scores WHERE minecraft_uuid = ? AND season = ? AND award_id = ?")) {
            stmt.setString(1, player);
            stmt.setString(2, season);
            stmt.setString(3, award);
            try (var rs = stmt.executeQuery()) {
                check(rs.next(), "score row is missing");
                return rs.getDouble(1);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
