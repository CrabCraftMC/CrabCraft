package crabcraft.net.crabUtilities.velocity;

import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter;
import net.kyori.adventure.text.Component;

import java.util.UUID;

final class NicknameRegressionTest {

    public static void main(String[] args) {
        parsesSupportedNicknameFormatsWithoutInteractiveTags();
        distinguishesUnknownFromLoadedEmptyState();
        rejectsStaleCacheGenerations();
        doesNotCommitFailedDatabaseLoads();
        reconcilesAtomicPublicationResult();
    }

    private static void parsesSupportedNicknameFormatsWithoutInteractiveTags() {
        checkPlain("&aCrab", "Crab");
        checkPlain("&#12Ab34Crab", "Crab");
        checkPlain("&x&1&2&A&B&3&4Crab", "Crab");
        checkPlain("§x§1§2§A§B§3§4Crab", "Crab");
        checkPlain("<aqua><bold>Crab</bold></aqua>", "Crab");
        checkPlain("<color:red>Crab</color>", "Crab");
        checkPlain("<gradient:red:blue>Crab</gradient>", "Crab");
        checkPlain("<rainbow>Crab</rainbow><reset>Craft", "CrabCraft");

        String malformed = "<gradient:not-a-colour>Crabby</gradient>";
        checkPlain(malformed, malformed);

        Component interactive = NicknameComponentParser.parse(
                "<click:run_command:'/op @s'><red>Crab</red></click>");
        check(!hasClickEvent(interactive), "nickname parser enabled a click tag");
    }

    private static void distinguishesUnknownFromLoadedEmptyState() {
        NicknameCache cache = new NicknameCache();
        UUID id = UUID.randomUUID();

        check(!cache.isLoaded(id), "new cache entry should be unknown");
        NicknameCache.Snapshot unknown = cache.beginLoad(id);
        check(cache.commitIfVersion(id, unknown.version(), ""),
                "empty Redis tombstone was not committed");
        check(cache.isLoaded(id), "loaded empty nickname was treated as unknown");
        check(cache.getRawNickname(id) == null, "empty nickname should have no display value");
        check("".equals(cache.snapshot(id).rawNickname()), "empty tombstone was not preserved");
    }

    private static void rejectsStaleCacheGenerations() {
        NicknameCache cache = new NicknameCache();
        UUID id = UUID.randomUUID();
        NicknameCache.Snapshot unknown = cache.beginLoad(id);

        cache.remove(id);
        check(!cache.commitIfVersion(id, unknown.version(), "<red>Old session</red>"),
                "unknown seed committed after disconnect invalidation");
        check(!cache.isLoaded(id), "disconnect invalidation became loaded state");

        NicknameCache.Snapshot currentUnknown = cache.beginLoad(id);
        check(cache.commitIfVersion(id, currentUnknown.version(), "<green>New</green>"),
                "current session could not commit after invalidation");

        check(!cache.commitIfVersion(id, unknown.version(), "<red>Old</red>"),
                "stale seed replaced a newer Redis update");
        check("<green>New</green>".equals(cache.getRawNickname(id)),
                "newer nickname was not preserved");

        long disconnectedVersion = cache.snapshot(id).version();
        cache.remove(id);
        cache.setNickname(id, "<blue>Reconnected</blue>");
        check(!cache.isVersion(id, disconnectedVersion),
                "cache generation was reused after reconnect");

        NicknameCache cleanupCache = new NicknameCache();
        UUID cleanupId = UUID.randomUUID();
        NicknameCache.Snapshot abandoned = cleanupCache.beginLoad(cleanupId);
        check(cleanupCache.discardIfUnloadedVersion(cleanupId, abandoned.version()),
                "abandoned unloaded generation was not discarded");
        check(cleanupCache.snapshot(cleanupId).version() == 0L,
                "read-only snapshot retained an abandoned UUID");

        NicknameCache.Snapshot newerLoad = cleanupCache.beginLoad(cleanupId);
        cleanupCache.setNickname(cleanupId, "<aqua>Live</aqua>");
        check(!cleanupCache.discardIfUnloadedVersion(cleanupId, newerLoad.version()),
                "cleanup discarded a newer live nickname");
        check("<aqua>Live</aqua>".equals(cleanupCache.getRawNickname(cleanupId)),
                "newer live nickname was not preserved during cleanup");
    }

    private static void doesNotCommitFailedDatabaseLoads() {
        NicknameCache cache = new NicknameCache();
        UUID id = UUID.randomUUID();

        check(!ConnectionListener.commitNicknameLoad(
                        cache, id, cache.beginLoad(id).version(),
                        PostgresStatsWriter.NicknameLoadResult.failed()),
                "failed database load reported a committed value");
        check(!cache.isLoaded(id), "failed database load committed a nickname clear");

        check(ConnectionListener.commitNicknameLoad(
                        cache, id, cache.beginLoad(id).version(),
                        PostgresStatsWriter.NicknameLoadResult.absent()),
                "successful absent database load was not committed");
        check(cache.isLoaded(id) && cache.getRawNickname(id) == null,
                "absent database nickname was not recorded as loaded empty state");
    }

    private static void reconcilesAtomicPublicationResult() {
        NicknameCache cache = new NicknameCache();
        UUID id = UUID.randomUUID();
        NicknameCache.Snapshot loading = cache.beginLoad(id);
        check(cache.commitIfVersion(id, loading.version(), "<red>Database</red>"),
                "database seed setup did not commit");
        NicknameCache.Snapshot proposed = cache.snapshot(id);

        check(NicknameListener.reconcilePublishedNickname(
                        cache, id, proposed.version(), proposed.rawNickname(), "<green>Live</green>"),
                "existing Redis nickname did not supersede the database seed");
        check("<green>Live</green>".equals(cache.getRawNickname(id)),
                "actual Redis nickname was not adopted locally");

        NicknameCache.Snapshot live = cache.snapshot(id);
        cache.setNickname(id, "<aqua>Newer</aqua>");
        check(!NicknameListener.reconcilePublishedNickname(
                        cache, id, live.version(), live.rawNickname(), "<yellow>Redis</yellow>"),
                "stale Redis result replaced a newer local nickname");
        check("<aqua>Newer</aqua>".equals(cache.getRawNickname(id)),
                "newer local nickname was not preserved");

        NicknameCache.Snapshot newer = cache.snapshot(id);
        check(NicknameListener.reconcilePublishedNickname(
                        cache, id, newer.version(), newer.rawNickname(), ""),
                "existing Redis clear tombstone was not reconciled");
        check(cache.isLoaded(id) && cache.getRawNickname(id) == null
                        && "".equals(cache.snapshot(id).rawNickname()),
                "Redis clear tombstone was not preserved locally");
    }

    private static void checkPlain(String raw, String expected) {
        check(expected.equals(NicknameComponentParser.plain(raw)),
                "nickname format did not parse: " + raw);
    }

    private static boolean hasClickEvent(Component component) {
        if (component.clickEvent() != null) return true;
        for (Component child : component.children()) {
            if (hasClickEvent(child)) return true;
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
