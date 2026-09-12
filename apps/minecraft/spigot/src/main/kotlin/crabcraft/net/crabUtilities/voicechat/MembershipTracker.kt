package crabcraft.net.crabUtilities.voicechat

import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tracks local group membership for inbound cross-server audio fan-out. */
class MembershipTracker {
    private val groupMembers = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /** SVC can move directly from A to B without a LeaveGroupEvent. */
    @Synchronized fun setLocalGroup(playerId: UUID, groupId: UUID?): UUID? {
        val previous = getLocalGroupOf(playerId)
        for (existingGroupId in groupMembers.keys.toSet()) {
            if (groupId == null || existingGroupId != groupId) removeLocal(existingGroupId, playerId)
        }
        if (groupId != null) groupMembers.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(playerId)
        return previous
    }

    @Synchronized fun onPlayerDisconnect(event: PlayerDisconnectedEvent) {
        val playerId = event.getPlayerUuid()
        for (groupId in groupMembers.keys.toSet()) removeLocal(groupId, playerId)
    }

    private fun removeLocal(groupId: UUID, playerId: UUID) {
        val members = groupMembers[groupId] ?: return
        members.remove(playerId)
        if (members.isEmpty()) groupMembers.remove(groupId)
    }

    fun getLocalMembers(groupId: UUID): Set<UUID> = groupMembers[groupId]?.let { java.util.Set.copyOf(it) } ?: emptySet()

    @Synchronized fun getLocalGroupOf(playerId: UUID): UUID? {
        for ((groupId, members) in groupMembers) if (members.contains(playerId)) return groupId
        return null
    }
}
