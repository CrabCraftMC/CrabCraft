package crabcraft.net.crabUtilities.velocity

import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter
import net.kyori.adventure.text.Component

import java.util.UUID

object NicknameRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        parsesSupportedNicknameFormatsWithoutInteractiveTags()
        distinguishesUnknownFromLoadedEmptyState()
        rejectsStaleCacheGenerations()
        doesNotCommitFailedDatabaseLoads()
        reconcilesAtomicPublicationResult()
    }

    private fun parsesSupportedNicknameFormatsWithoutInteractiveTags() {
        checkPlain("&aCrab", "Crab")
        checkPlain("&#12Ab34Crab", "Crab")
        checkPlain("&x&1&2&A&B&3&4Crab", "Crab")
        checkPlain("§x§1§2§A§B§3§4Crab", "Crab")
        checkPlain("<aqua><bold>Crab</bold></aqua>", "Crab")
        checkPlain("<color:red>Crab</color>", "Crab")
        checkPlain("<gradient:red:blue>Crab</gradient>", "Crab")
        checkPlain("<rainbow>Crab</rainbow><reset>Craft", "CrabCraft")

        val malformed = "<gradient:not-a-colour>Crabby</gradient>"
        checkPlain(malformed, malformed)

        val interactive = NicknameComponentParser.parse(
                "<click:run_command:'/op @s'><red>Crab</red></click>")
        check(!hasClickEvent(interactive), "nickname parser enabled a click tag")
    }

    private fun distinguishesUnknownFromLoadedEmptyState() {
        val cache = NicknameCache()
        val id = UUID.randomUUID()

        check(!cache.isLoaded(id), "new cache entry should be unknown")
        val unknown = cache.beginLoad(id)
        check(cache.commitIfVersion(id, unknown.version(), ""),
                "empty Redis tombstone was not committed")
        check(cache.isLoaded(id), "loaded empty nickname was treated as unknown")
        check(cache.getRawNickname(id) == null, "empty nickname should have no display value")
        check("".equals(cache.snapshot(id).rawNickname()), "empty tombstone was not preserved")
    }

    private fun rejectsStaleCacheGenerations() {
        val cache = NicknameCache()
        val id = UUID.randomUUID()
        val unknown = cache.beginLoad(id)

        cache.remove(id)
        check(!cache.commitIfVersion(id, unknown.version(), "<red>Old session</red>"),
                "unknown seed committed after disconnect invalidation")
        check(!cache.isLoaded(id), "disconnect invalidation became loaded state")

        val currentUnknown = cache.beginLoad(id)
        check(cache.commitIfVersion(id, currentUnknown.version(), "<green>New</green>"),
                "current session could not commit after invalidation")

        check(!cache.commitIfVersion(id, unknown.version(), "<red>Old</red>"),
                "stale seed replaced a newer Redis update")
        check("<green>New</green>".equals(cache.getRawNickname(id)),
                "newer nickname was not preserved")

        val disconnectedVersion = cache.snapshot(id).version()
        cache.remove(id)
        cache.setNickname(id, "<blue>Reconnected</blue>")
        check(!cache.isVersion(id, disconnectedVersion),
                "cache generation was reused after reconnect")

        val cleanupCache = NicknameCache()
        val cleanupId = UUID.randomUUID()
        val abandoned = cleanupCache.beginLoad(cleanupId)
        check(cleanupCache.discardIfUnloadedVersion(cleanupId, abandoned.version()),
                "abandoned unloaded generation was not discarded")
        check(cleanupCache.snapshot(cleanupId).version() == 0L,
                "read-only snapshot retained an abandoned UUID")

        val newerLoad = cleanupCache.beginLoad(cleanupId)
        cleanupCache.setNickname(cleanupId, "<aqua>Live</aqua>")
        check(!cleanupCache.discardIfUnloadedVersion(cleanupId, newerLoad.version()),
                "cleanup discarded a newer live nickname")
        check("<aqua>Live</aqua>".equals(cleanupCache.getRawNickname(cleanupId)),
                "newer live nickname was not preserved during cleanup")
    }

    private fun doesNotCommitFailedDatabaseLoads() {
        val cache = NicknameCache()
        val id = UUID.randomUUID()

        check(!ConnectionListener.commitNicknameLoad(
                        cache, id, cache.beginLoad(id).version(),
                        PostgresStatsWriter.NicknameLoadResult.failed()),
                "failed database load reported a committed value")
        check(!cache.isLoaded(id), "failed database load committed a nickname clear")

        check(ConnectionListener.commitNicknameLoad(
                        cache, id, cache.beginLoad(id).version(),
                        PostgresStatsWriter.NicknameLoadResult.absent()),
                "successful absent database load was not committed")
        check(cache.isLoaded(id) && cache.getRawNickname(id) == null,
                "absent database nickname was not recorded as loaded empty state")
    }

    private fun reconcilesAtomicPublicationResult() {
        val cache = NicknameCache()
        val id = UUID.randomUUID()
        val loading = cache.beginLoad(id)
        check(cache.commitIfVersion(id, loading.version(), "<red>Database</red>"),
                "database seed setup did not commit")
        val proposed = cache.snapshot(id)

        check(NicknameListener.reconcilePublishedNickname(
                        cache, id, proposed.version(), proposed.rawNickname()!!, "<green>Live</green>"),
                "existing Redis nickname did not supersede the database seed")
        check("<green>Live</green>".equals(cache.getRawNickname(id)),
                "actual Redis nickname was not adopted locally")

        val live = cache.snapshot(id)
        cache.setNickname(id, "<aqua>Newer</aqua>")
        check(!NicknameListener.reconcilePublishedNickname(
                        cache, id, live.version(), live.rawNickname()!!, "<yellow>Redis</yellow>"),
                "stale Redis result replaced a newer local nickname")
        check("<aqua>Newer</aqua>".equals(cache.getRawNickname(id)),
                "newer local nickname was not preserved")

        val newer = cache.snapshot(id)
        check(NicknameListener.reconcilePublishedNickname(
                        cache, id, newer.version(), newer.rawNickname()!!, ""),
                "existing Redis clear tombstone was not reconciled")
        check(cache.isLoaded(id) && cache.getRawNickname(id) == null
                        && "".equals(cache.snapshot(id).rawNickname()),
                "Redis clear tombstone was not preserved locally")
    }

    private fun checkPlain(raw: String, expected: String) {
        check(expected.equals(NicknameComponentParser.plain(raw)),
                "nickname format did not parse: " + raw)
    }

    private fun hasClickEvent(component: Component): Boolean {
        if (component.clickEvent() != null) return true
        for (child in component.children()) {
            if (hasClickEvent(child)) return true
        }
        return false
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
