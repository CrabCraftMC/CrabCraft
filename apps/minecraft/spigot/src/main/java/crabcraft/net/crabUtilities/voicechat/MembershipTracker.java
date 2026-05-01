package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
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

    void onJoinGroupEvent(JoinGroupEvent event) {
        Group group = event.getGroup();
        VoicechatConnection conn = event.getConnection();
        if (group == null || conn == null) return;
        groupMembers
                .computeIfAbsent(group.getId(), k -> ConcurrentHashMap.newKeySet())
                .add(conn.getPlayer().getUuid());
    }

    void onLeaveGroupEvent(LeaveGroupEvent event) {
        VoicechatConnection conn = event.getConnection();
        if (conn == null) return;
        Group group = event.getGroup();
        if (group == null) return;
        removeLocal(group.getId(), conn.getPlayer().getUuid());
    }

    void onPlayerDisconnect(PlayerDisconnectedEvent event) {
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
}
