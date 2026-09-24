package crabcraft.net.crabUtilities.velocity

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

open class NicknameCache {
    data class Snapshot(private val loaded: Boolean, private val rawNickname: String?, private val version: Long) {
        fun loaded() = loaded

        fun rawNickname() = rawNickname

        fun version() = version
    }

    private data class Entry(val loaded: Boolean, val rawNickname: String, val version: Long)

    private val nicknames = ConcurrentHashMap<UUID, Entry>()
    private val versions = AtomicLong()

    open fun setNickname(uuid: UUID, rawNickname: String?) {
        val normalised = rawNickname ?: ""
        nicknames.compute(uuid) { _, _ -> Entry(true, normalised, versions.incrementAndGet()) }
    }

    open fun getRawNickname(uuid: UUID): String? {
        val entry = nicknames[uuid]
        return if (entry == null || !entry.loaded || entry.rawNickname.isEmpty()) null else entry.rawNickname
    }

    open fun getPlainNickname(uuid: UUID): String? = getRawNickname(uuid)?.let(::stripColors)

    open fun isLoaded(uuid: UUID): Boolean = nicknames[uuid]?.loaded == true

    open fun snapshot(uuid: UUID): Snapshot = nicknames[uuid]?.let(::snapshot) ?: Snapshot(false, null, 0L)

    open fun beginLoad(uuid: UUID): Snapshot =
        snapshot(
            nicknames.computeIfAbsent(uuid) {
                Entry(false, "", versions.incrementAndGet())
            }
        )

    open fun commitIfVersion(uuid: UUID, expectedVersion: Long, rawNickname: String?): Boolean {
        val normalised = rawNickname ?: ""
        val committed = AtomicBoolean()
        nicknames.compute(uuid) { _, current ->
            if (current == null || current.version != expectedVersion) current
            else {
                committed.set(true)
                Entry(true, normalised, versions.incrementAndGet())
            }
        }
        return committed.get()
    }

    open fun discardIfUnloadedVersion(uuid: UUID, expectedVersion: Long): Boolean {
        val discarded = AtomicBoolean()
        nicknames.computeIfPresent(uuid) { _, current ->
            if (current.loaded || current.version != expectedVersion) current
            else {
                discarded.set(true)
                null
            }
        }
        return discarded.get()
    }

    open fun isVersion(uuid: UUID, version: Long): Boolean {
        val entry = nicknames[uuid]
        return entry != null && entry.loaded && entry.version == version
    }

    open fun remove(uuid: UUID) {
        nicknames.remove(uuid)
    }

    private fun snapshot(entry: Entry) =
        Snapshot(entry.loaded, entry.rawNickname.takeIf { entry.loaded }, entry.version)

    companion object {
        @JvmStatic fun stripColors(text: String?): String? = NicknameComponentParser.plain(text)
    }
}
