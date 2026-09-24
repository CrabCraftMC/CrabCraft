package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Locally committed membership used by cross-server audio fan-out. */
open class MembershipTracker {
    private val groupMembers = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /** SVC can move directly between groups without a LeaveGroupEvent. */
    @Synchronized
    open fun setLocalGroup(playerId: UUID, groupId: UUID?): UUID? {
        val previous = getLocalGroupOf(playerId)
        if (previous != null && previous != groupId) removeLocal(previous, playerId)
        if (groupId != null) groupMembers.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(playerId)
        return previous
    }

    @Synchronized
    open fun onPlayerDisconnect(event: PlayerDisconnectedEvent) {
        setLocalGroup(event.playerUuid, null)
    }

    private fun removeLocal(groupId: UUID, playerId: UUID) {
        val members = groupMembers[groupId] ?: return
        members.remove(playerId)
        if (members.isEmpty()) groupMembers.remove(groupId)
    }

    open fun getLocalMembers(groupId: UUID): Set<UUID> =
        groupMembers[groupId]?.let { java.util.Set.copyOf(it) } ?: emptySet()

    @Synchronized
    open fun getLocalGroupOf(playerId: UUID): UUID? = groupMembers.entries.firstOrNull { playerId in it.value }?.key
}
