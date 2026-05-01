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
import java.util.function.Consumer;

/**
 * Tracks which players (across all backends) are in which group, by
 * applying delta messages from Redis. Local membership changes are
 * published as {@code MEMBER_JOIN}/{@code MEMBER_LEAVE} so other
 * backends can do the same.
 *
 * <p>The receiving backend uses this map to compute the target set of
 * a {@link de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel}
 * so cross-server audio reaches the right local players.
 */
class MembershipTracker {

    private final RedisVoiceBus bus;
    private final String thisBackend;

    /** groupId -> (playerId -> backendName) across all backends, including this one. */
    private final Map<UUID, Map<UUID, String>> groupMembers = new ConcurrentHashMap<>();

    /** Hook the receiving side can use to react to membership changes (e.g. recompute audio targets). */
    private Consumer<UUID> onGroupChanged = id -> {};

    MembershipTracker(RedisVoiceBus bus, String thisBackend) {
        this.bus = bus;
        this.thisBackend = thisBackend;
    }

    void setOnGroupChanged(Consumer<UUID> hook) {
        this.onGroupChanged = hook;
    }

    void onJoinGroupEvent(JoinGroupEvent event) {
        Group group = event.getGroup();
        VoicechatConnection conn = event.getConnection();
        if (group == null || conn == null) return;
        UUID groupId = group.getId();
        UUID playerId = conn.getPlayer().getUuid();
        recordJoin(groupId, playerId, thisBackend);
        bus.publishLifecycle(VoiceMessages.encodeMemberJoin(groupId, playerId, thisBackend));
    }

    void onLeaveGroupEvent(LeaveGroupEvent event) {
        VoicechatConnection conn = event.getConnection();
        if (conn == null) return;
        Group group = conn.getGroup();
        if (group == null) return;
        UUID groupId = group.getId();
        UUID playerId = conn.getPlayer().getUuid();
        recordLeave(groupId, playerId);
        bus.publishLifecycle(VoiceMessages.encodeMemberLeave(groupId, playerId, thisBackend));
    }

    void onPlayerDisconnect(PlayerDisconnectedEvent event) {
        UUID playerId = event.getPlayerUuid();
        for (UUID groupId : Set.copyOf(groupMembers.keySet())) {
            Map<UUID, String> members = groupMembers.get(groupId);
            if (members != null && members.containsKey(playerId)) {
                recordLeave(groupId, playerId);
                bus.publishLifecycle(VoiceMessages.encodeMemberLeave(groupId, playerId, thisBackend));
            }
        }
        bus.publishLifecycle(VoiceMessages.encodeSpeakerLeft(playerId, thisBackend));
    }

    void applyMemberJoin(UUID groupId, UUID playerId, String backend) {
        recordJoin(groupId, playerId, backend);
    }

    void applyMemberLeave(UUID groupId, UUID playerId) {
        recordLeave(groupId, playerId);
    }

    private void recordJoin(UUID groupId, UUID playerId, String backend) {
        groupMembers
                .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                .put(playerId, backend);
        onGroupChanged.accept(groupId);
    }

    private void recordLeave(UUID groupId, UUID playerId) {
        Map<UUID, String> members = groupMembers.get(groupId);
        if (members != null) {
            members.remove(playerId);
            if (members.isEmpty()) {
                groupMembers.remove(groupId);
            }
        }
        onGroupChanged.accept(groupId);
    }

    /** Members of a group on THIS backend only — used to build StaticAudioChannel targets. */
    Set<UUID> getLocalMembers(UUID groupId) {
        Map<UUID, String> members = groupMembers.get(groupId);
        if (members == null) return Set.of();
        Set<UUID> local = new java.util.HashSet<>();
        for (Map.Entry<UUID, String> e : members.entrySet()) {
            if (thisBackend.equals(e.getValue())) local.add(e.getKey());
        }
        return local;
    }
}
