package crabcraft.net.crabUtilities.velocity.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import crabcraft.net.crabUtilities.tags.TagBridgeProtocol.Status;
import crabcraft.net.crabUtilities.tags.TagSnapshot;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicit integration check; only uses a disposable schema in the configured test database. */
final class TagDbIntegrationTest {
    private static final String PUMPKIN = "halloween-2026-pumpkin";
    private static final String SECOND = "integration-second";

    public static void main(String[] args) throws Exception {
        String url = System.getenv("CRABCRAFT_TEST_JDBC_URL");
        if (url == null) throw new IllegalArgumentException("Set CRABCRAFT_TEST_JDBC_URL to a disposable PostgreSQL database");
        String schema = "tags_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url); var statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(url);
                config.setSchema(schema);
                config.setMaximumPoolSize(6);
                try (var db = new HikariDataSource(config)) {
                    var repository = new TagRepository(db, LoggerFactory.getLogger(TagDbIntegrationTest.class));
                    execute(db, """
                            INSERT INTO cosmetic_tags (id, name, description, glyph_id, sort_order)
                            VALUES ('integration-second', 'Second test tag', 'Integration test', 'test', 20)
                            """);
                    ownershipAndSelection(db, repository);
                    catalogueRefresh(db, repository);
                    constraintsAndRollback(db, repository);
                    concurrentMutations(db, repository);
                    crossRuntime(url, schema, repository);
                    bundledCataloguePreservesAdminChanges(db, repository);
                    System.out.println("Tags: seed, ownership, selection, revisions, constraints, rollback and concurrent snapshot checks passed.");
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void ownershipAndSelection(HikariDataSource db, TagRepository repository) throws Exception {
        UUID player = UUID.randomUUID();
        var empty = repository.load(player);
        check(empty.revision() == 0 && empty.selectedTagId() == null && empty.ownedTags().isEmpty(),
                "new player did not load an explicit empty snapshot");
        check(count(db, "player_tag_state", player) == 0, "read created persistent state");

        var unknown = repository.select(player, "missing", 0);
        check(unknown.status() == Status.UNKNOWN_TAG && !unknown.changed(), "unknown definition was selectable");
        check(repository.select(player, PUMPKIN, 0).status() == Status.NOT_OWNED, "unowned tag was selectable");
        var grant = repository.grant(player, PUMPKIN, "manual", "console", "Integration grant");
        check(grant.status() == Status.OK && grant.changed() && grant.snapshot().revision() == 1,
                "first grant did not increment revision once");
        check(grant.snapshot().selectedTagId() == null, "grant auto-equipped a tag");
        var duplicate = repository.grant(player, PUMPKIN, "manual", "console", "Duplicate grant");
        check(!duplicate.changed() && duplicate.snapshot().revision() == 1, "duplicate grant changed state");
        check(count(db, "player_tag_audit", player) == 1, "duplicate grant added an audit record");

        var selected = repository.select(player, PUMPKIN, 1);
        check(selected.changed() && selected.snapshot().revision() == 2
                && PUMPKIN.equals(selected.snapshot().selectedTagId()), "selection was not persisted");
        check(!repository.select(player, PUMPKIN, 2).changed(), "same selection incremented revision");
        check(repository.select(player, null, 1).status() == Status.STALE_STATE, "stale revision was accepted");
        repository.grant(player, SECOND, "manual", "console", "Second grant");
        var replaced = repository.select(player, SECOND, 3);
        check(replaced.changed() && SECOND.equals(replaced.snapshot().selectedTagId())
                && replaced.snapshot().ownedTags().size() == 2, "selection replaced ownership or stacked tags");
        var cleared = repository.select(player, null, 4);
        check(cleared.changed() && cleared.snapshot().selectedTagId() == null && cleared.snapshot().revision() == 5,
                "explicit None was not persisted");

        var restarted = new TagRepository(db, LoggerFactory.getLogger(TagDbIntegrationTest.class));
        var persisted = restarted.load(player);
        check(persisted.revision() == 5 && persisted.selectedTagId() == null && persisted.ownedTags().size() == 2,
                "restart/seed reset player ownership or selection");
        restarted.select(player, PUMPKIN, 5);
        var revoked = restarted.revoke(player, PUMPKIN, "console", "Incorrect award");
        check(revoked.changed() && revoked.snapshot().revision() == 7 && revoked.snapshot().selectedTagId() == null
                && revoked.snapshot().ownedTags().size() == 1, "equipped revoke was not atomic");
        check(!restarted.revoke(player, PUMPKIN, "console", "Repeated revoke").changed(), "repeated revoke changed state");
        check(count(db, "player_tag_audit", player) == 3, "manual audit history does not match effective mutations");
    }

    private static void catalogueRefresh(HikariDataSource db, TagRepository repository) throws Exception {
        UUID player = UUID.randomUUID();
        repository.grant(player, SECOND, "manual", "console", "Disabled selection test");
        repository.select(player, SECOND, 1);
        execute(db, "UPDATE cosmetic_tags SET enabled = FALSE, name = 'Temporarily unavailable' WHERE id = 'integration-second'");
        var disabled = repository.load(player);
        check(disabled.revision() == 2 && SECOND.equals(disabled.selectedTagId())
                && !disabled.ownedTags().getFirst().enabled()
                && disabled.ownedTags().getFirst().name().equals("Temporarily unavailable"),
                "catalogue metadata did not refresh without a revision change");
        check(!repository.select(player, SECOND, 2).changed(), "retaining disabled choice changed its dormant state");
        UUID other = UUID.randomUUID();
        repository.grant(other, SECOND, "manual", "console", "Disabled entitlement");
        check(repository.select(other, SECOND, 1).status() == Status.DISABLED, "disabled tag was newly selectable");
        execute(db, "UPDATE cosmetic_tags SET enabled = TRUE WHERE id = 'integration-second'");
        check(SECOND.equals(repository.load(player).selectedTagId()) && repository.load(player).ownedTags().getFirst().enabled(),
                "re-enable lost saved selection");
        repository.select(player, null, 2);
        check(repository.load(player).selectedTagId() == null, "None did not clear disabled/restored choice");
    }

    private static void bundledCataloguePreservesAdminChanges(HikariDataSource db, TagRepository repository) throws Exception {
        UUID player = UUID.randomUUID();
        var grant = repository.grant(player, PUMPKIN, "manual", "console", "Catalogue persistence test");
        var bundled = grant.snapshot().ownedTags().getFirst();
        check(bundled.name().equals("Halloween 2026")
                && bundled.description().equals("Complete all three Halloween 2026 challenges.")
                && bundled.glyphId().equals("pumpkin") && bundled.sortOrder() == 10 && bundled.enabled(),
                "empty database did not receive bundled defaults");
        repository.select(player, PUMPKIN, 1);
        execute(db, """
                UPDATE cosmetic_tags SET name = 'Edited Halloween', description = 'Changed by an administrator',
                    glyph_id = 'edited_pumpkin', sort_order = 42, enabled = FALSE
                WHERE id = 'halloween-2026-pumpkin'
                """);

        var restarted = new TagRepository(db, LoggerFactory.getLogger(TagDbIntegrationTest.class));
        var persisted = restarted.load(player);
        var edited = persisted.ownedTags().getFirst();
        check(edited.name().equals("Edited Halloween")
                && edited.description().equals("Changed by an administrator")
                && edited.glyphId().equals("edited_pumpkin") && edited.sortOrder() == 42 && !edited.enabled(),
                "restart replaced administrator changes with bundled defaults");
        check(persisted.revision() == 2 && PUMPKIN.equals(persisted.selectedTagId())
                && persisted.ownedTags().size() == 1 && count(db, "player_tag_audit", player) == 1,
                "catalogue reseeding changed ownership, selection or award history");
    }

    private static void constraintsAndRollback(HikariDataSource db, TagRepository repository) throws Exception {
        UUID player = UUID.randomUUID();
        repository.select(player, null, 0);
        expectSqlFailure(() -> execute(db, "UPDATE player_tag_state SET selected_tag_id = '" + PUMPKIN
                + "' WHERE minecraft_uuid = '" + player + "'"), "FK accepted an unowned selected tag");
        expectSqlFailure(() -> execute(db, "UPDATE player_tag_state SET revision = -1 WHERE minecraft_uuid = '"
                + player + "'"), "negative revision was accepted");
        execute(db, "ALTER TABLE player_tag_audit ADD CONSTRAINT integration_reject_reason CHECK (reason <> 'reject-test')");
        expectSqlFailure(() -> repository.grant(player, PUMPKIN, "manual", "console", "reject-test"),
                "grant did not propagate audit failure");
        check(repository.load(player).revision() == 0 && repository.load(player).ownedTags().isEmpty()
                && count(db, "player_tag_audit", player) == 0, "failed grant partially committed");
        repository.grant(player, PUMPKIN, "manual", "console", "Valid grant");
        repository.select(player, PUMPKIN, 1);
        expectSqlFailure(() -> repository.revoke(player, PUMPKIN, "console", "reject-test"),
                "revoke did not propagate audit failure");
        var after = repository.load(player);
        check(after.revision() == 2 && PUMPKIN.equals(after.selectedTagId()) && after.ownedTags().size() == 1
                && count(db, "player_tag_audit", player) == 1, "failed revoke partially committed");
        execute(db, "ALTER TABLE player_tag_audit DROP CONSTRAINT integration_reject_reason");
    }

    private static void concurrentMutations(HikariDataSource db, TagRepository repository) throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            for (int iteration = 0; iteration < 24; iteration++) {
                UUID player = UUID.randomUUID();
                repository.grant(player, PUMPKIN, "manual", "console", "Concurrent grant");
                var start = new CountDownLatch(1);
                var selected = workers.submit(() -> {
                    start.await();
                    return repository.select(player, PUMPKIN, 1);
                });
                var revoked = workers.submit(() -> {
                    start.await();
                    return repository.revoke(player, PUMPKIN, "console", "Concurrent revoke");
                });
                var reads = workers.submit(() -> {
                    start.await();
                    for (int read = 0; read < 12; read++) validateSnapshot(repository.load(player));
                    return null;
                });
                start.countDown();
                var selection = selected.get();
                var revocation = revoked.get();
                reads.get();
                check(revocation.status() == Status.OK && revocation.changed(), "concurrent revoke failed");
                check(selection.status() == Status.OK || selection.status() == Status.STALE_STATE,
                        "serialised select/revoke produced an unexpected status");
                var finalState = repository.load(player);
                check(finalState.selectedTagId() == null && finalState.ownedTags().isEmpty(),
                        "concurrent revoke left an equipped/unlocked tag");
                check(finalState.revision() == (selection.changed() ? 3 : 2),
                        "concurrent mutation revision lost a change");
                check(count(db, "player_tag_audit", player) == 2, "concurrent history is incomplete");
            }
            UUID player = UUID.randomUUID();
            var start = new CountDownLatch(1);
            var first = workers.submit(() -> {
                start.await();
                return repository.grant(player, PUMPKIN, "manual", "console", "First concurrent grant");
            });
            var second = workers.submit(() -> {
                start.await();
                return repository.grant(player, PUMPKIN, "manual", "console", "Second concurrent grant");
            });
            start.countDown();
            check(first.get().changed() != second.get().changed(), "simultaneous initial grants both changed ownership");
            check(repository.load(player).revision() == 1 && count(db, "player_tag_audit", player) == 1,
                    "initial state-row creation did not serialise grants");

            for (int iteration = 0; iteration < 12; iteration++) {
                UUID target = UUID.randomUUID();
                repository.grant(target, PUMPKIN, "manual", "console", "Initial concurrent ownership");
                var race = new CountDownLatch(1);
                var grant = workers.submit(() -> {
                    race.await();
                    return repository.grant(target, PUMPKIN, "manual", "console", "Racing grant");
                });
                var revoke = workers.submit(() -> {
                    race.await();
                    return repository.revoke(target, PUMPKIN, "console", "Racing revoke");
                });
                race.countDown();
                var granted = grant.get();
                check(revoke.get().changed(), "racing revoke missed initial ownership");
                var result = repository.load(target);
                check(result.revision() == (granted.changed() ? 3 : 2)
                        && result.ownedTags().isEmpty() != granted.changed(), "grant/revoke lost serial ordering");
            }
        }
    }

    private static void validateSnapshot(TagSnapshot snapshot) {
        String selected = snapshot.selectedTagId();
        check(selected == null || snapshot.ownedTags().stream().anyMatch(tag -> tag.id().equals(selected)),
                "read combined selected state with ownership from another transaction");
        check(snapshot.revision() >= 0, "negative snapshot revision");
    }

    private static void crossRuntime(String jdbcUrl, String schema, TagRepository repository) throws Exception {
        String repositoryRoot = System.getenv("CRABCRAFT_TEST_REPOSITORY_ROOT");
        if (repositoryRoot == null) throw new IllegalArgumentException("Run via :velocity:tagDbIntegrationTest to locate the Bun fixture");
        String databaseUrl = System.getenv("CRABCRAFT_TEST_DATABASE_URL");
        if (databaseUrl == null) databaseUrl = jdbcUrl.replaceFirst("^jdbc:", "");
        runBunFixture(repositoryRoot, databaseUrl, schema, "prepare");
        UUID player = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var fromTypescript = repository.load(player);
        check(fromTypescript.revision() == 1 && fromTypescript.selectedTagId() == null
                && fromTypescript.ownedTags().stream().anyMatch(tag -> tag.id().equals(PUMPKIN)),
                "Java could not read the TypeScript award");
        check(repository.select(player, PUMPKIN, 1).snapshot().revision() == 2, "Java could not select the TypeScript award");
        check(repository.revoke(player, PUMPKIN, "console", "Cross-runtime revoke").snapshot().revision() == 3,
                "Java could not revoke the TypeScript award");
        check(repository.grant(player, PUMPKIN, "manual", "console", "Cross-runtime regrant").snapshot().revision() == 4,
                "Java could not explicitly regrant after automatic award revocation");
        check(repository.select(player, PUMPKIN, 4).snapshot().revision() == 5, "Java regrant was not selectable");
        runBunFixture(repositoryRoot, databaseUrl, schema, "verify");
    }

    private static void runBunFixture(String repositoryRoot, String databaseUrl, String schema, String phase)
            throws Exception {
        var process = new ProcessBuilder("bun", "packages/db/tests/tagCrossRuntimeFixture.ts", "--phase", phase)
                .directory(new File(repositoryRoot)).inheritIO();
        process.environment().put("CRABCRAFT_TEST_DATABASE_URL", databaseUrl);
        process.environment().put("CRABCRAFT_TEST_SCHEMA", schema);
        var running = process.start();
        if (!running.waitFor(30, TimeUnit.SECONDS)) {
            running.destroyForcibly();
            throw new AssertionError("Bun cross-runtime fixture timed out during " + phase);
        }
        check(running.exitValue() == 0, "Bun cross-runtime fixture failed during " + phase);
    }

    private static long count(HikariDataSource db, String table, UUID player) throws SQLException {
        try (var connection = db.getConnection(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE minecraft_uuid = ?")) {
            statement.setString(1, player.toString());
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void execute(HikariDataSource db, String sql) throws SQLException {
        try (var connection = db.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void expectSqlFailure(SqlAction action, String message) throws Exception {
        try {
            action.run();
        } catch (SQLException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }
}
