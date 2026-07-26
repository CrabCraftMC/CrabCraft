package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks LOCAL group membership on this backend. AudioRelay uses
 * {@link #getLocalMembers} to populate the targets of an inbound
 * cross-server audio channel.
 *
 * <p>No cross-backend pub/sub: each backend independently observes its
 * own SVC events (this is enough because the only consumer is the local
 * audio-fanout, which only needs local players).
 */
class MembershipTracker {

    /** groupId -> local player UUIDs in that group. */
    private final Map<UUID, Set<UUID>> groupMembers = new ConcurrentHashMap<>();

    /**
     * Replaces the player's locally committed group and returns the previous
     * group. SVC can move directly from A to B without a LeaveGroupEvent, so
     * player membership must be single-valued.
     */
    synchronized UUID setLocalGroup(UUID playerId, UUID groupId) {
        UUID previous = getLocalGroupOf(playerId);
        for (UUID existingGroupId : Set.copyOf(groupMembers.keySet())) {
            if (groupId == null || !existingGroupId.equals(groupId)) {
                removeLocal(existingGroupId, playerId);
            }
        }
        if (groupId != null) {
            groupMembers
                    .computeIfAbsent(groupId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(playerId);
        }
        return previous;
    }

    synchronized void onPlayerDisconnect(PlayerDisconnectedEvent event) {
        UUID playerId = event.getPlayerUuid();
        for (UUID groupId : Set.copyOf(groupMembers.keySet())) {
            removeLocal(groupId, playerId);
        }
    }

    private void removeLocal(UUID groupId, UUID playerId) {
        Set<UUID> members = groupMembers.get(groupId);
        if (members == null) return;
        members.remove(playerId);
        if (members.isEmpty()) groupMembers.remove(groupId);
    }

    Set<UUID> getLocalMembers(UUID groupId) {
        Set<UUID> members = groupMembers.get(groupId);
        return members == null ? Set.of() : Set.copyOf(members);
    }

    synchronized UUID getLocalGroupOf(UUID playerId) {
        for (Map.Entry<UUID, Set<UUID>> entry : groupMembers.entrySet()) {
            if (entry.getValue().contains(playerId)) return entry.getKey();
        }
        return null;
    }
}
