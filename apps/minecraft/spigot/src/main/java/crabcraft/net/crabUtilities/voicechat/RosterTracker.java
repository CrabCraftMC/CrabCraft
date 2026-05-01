package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tracks which cross-server players are in which group, across all
 * backends, by applying delta messages from Redis. When the roster
 * changes, drives {@link SkinSyncer} and {@link SvcPacketSender} to
 * update the relevant local clients so cross-server members appear in
 * the SVC GUI roster with their real skin.
 */
class RosterTracker {

    /** Time without a BACKEND_ALIVE ping before we drop a backend's entries. */
    private static final long BACKEND_TIMEOUT_MS = 90_000L;

    private final CrabUtilities plugin;
    private final MembershipTracker membership;
    private final SvcPacketSender svcPackets;
    private final SkinSyncer skinSyncer;
    private final Set<UUID> crossServerGroupIds;
    private final String thisBackend;
    private final Logger logger;

    /** groupId -> playerId -> remote member metadata. */
    private final Map<UUID, Map<UUID, RemoteMember>> remoteByGroup = new ConcurrentHashMap<>();

    /** When was the last BACKEND_ALIVE ping seen from each backend (millis). */
    private final Map<String, Long> lastSeenAt = new ConcurrentHashMap<>();

    RosterTracker(CrabUtilities plugin, MembershipTracker membership,
                  SvcPacketSender svcPackets, SkinSyncer skinSyncer,
                  Set<UUID> crossServerGroupIds, String thisBackend, Logger logger) {
        this.plugin = plugin;
        this.membership = membership;
        this.svcPackets = svcPackets;
        this.skinSyncer = skinSyncer;
        this.crossServerGroupIds = crossServerGroupIds;
        this.thisBackend = thisBackend;
        this.logger = logger;
    }

    /* ----------------------- Inbound from Redis ----------------------- */

    void onLifecycleMessage(String message) {
        if (message == null || message.isEmpty()) return;
        int sep = message.indexOf(VoiceMessages.SEP);
        String op = sep < 0 ? message : message.substring(0, sep);
        switch (op) {
            case VoiceMessages.OP_ROSTER_JOIN -> handleJoin(message);
            case VoiceMessages.OP_ROSTER_LEAVE -> handleLeave(message);
            case VoiceMessages.OP_BACKEND_ALIVE -> handleBackendAlive(message);
            default -> {}
        }
    }

    private void handleJoin(String message) {
        VoiceMessages.RosterJoin join = VoiceMessages.decodeRosterJoin(message);
        if (join == null) return;
        if (thisBackend.equals(join.backend())) return;
        if (!crossServerGroupIds.contains(join.groupId())) return;

        ProfileCodec.Snapshot snapshot = ProfileCodec.decode(join.encodedProfile());
        if (snapshot == null) return;

        // Treat the receipt as an aliveness signal too — saves a tick.
        lastSeenAt.put(join.backend(), System.currentTimeMillis());

        Map<UUID, RemoteMember> groupMap = remoteByGroup
                .computeIfAbsent(join.groupId(), k -> new ConcurrentHashMap<>());
        RemoteMember existing = groupMap.get(snapshot.uuid());
        RemoteMember member = new RemoteMember(snapshot.uuid(), snapshot.name(),
                snapshot, join.backend());

        // Dedupe: if we already had this same (group, player, backend, profile)
        // entry, this is a periodic re-broadcast — silently update the
        // bookkeeping without re-pushing PlayerInfoUpdate to local listeners.
        if (existing != null && existing.equals(member)) return;

        groupMap.put(snapshot.uuid(), member);
        logger.info("Roster join: " + snapshot.name() + " (" + snapshot.uuid()
                + ") in group " + join.groupId() + " from backend '" + join.backend() + "'");
        Bukkit.getScheduler().runTask(plugin,
                () -> pushMemberToLocalListeners(join.groupId(), member));
    }

    private void handleLeave(String message) {
        VoiceMessages.RosterLeave leave = VoiceMessages.decodeRosterLeave(message);
        if (leave == null) return;
        if (thisBackend.equals(leave.backend())) return;
        if (!crossServerGroupIds.contains(leave.groupId())) return;

        Map<UUID, RemoteMember> members = remoteByGroup.get(leave.groupId());
        if (members == null) return;
        RemoteMember removed = members.remove(leave.playerId());
        if (members.isEmpty()) remoteByGroup.remove(leave.groupId());
        if (removed == null) return;

        Bukkit.getScheduler().runTask(plugin,
                () -> removeMemberFromLocalListeners(leave.groupId(), removed));
    }

    private void handleBackendAlive(String message) {
        String backend = VoiceMessages.decodeBackendAlive(message);
        if (backend == null || thisBackend.equals(backend)) return;
        lastSeenAt.put(backend, System.currentTimeMillis());
    }

    /**
     * Drops every roster entry from backends we haven't heard from in
     * {@link #BACKEND_TIMEOUT_MS}. Called from a periodic timer in the
     * plugin so that a {@code kill -9}'d backend doesn't leave its
     * members hanging in the UI forever.
     */
    void sweepStaleBackends() {
        long now = System.currentTimeMillis();
        Set<String> dead = new java.util.HashSet<>();
        for (Map.Entry<UUID, Map<UUID, RemoteMember>> g : remoteByGroup.entrySet()) {
            for (RemoteMember m : g.getValue().values()) {
                Long seenAt = lastSeenAt.get(m.backend());
                if (seenAt == null || now - seenAt > BACKEND_TIMEOUT_MS) {
                    dead.add(m.backend());
                }
            }
        }
        if (dead.isEmpty()) return;

        for (String backend : dead) {
            logger.info("Roster sweep: backend '" + backend
                    + "' hasn't pinged in " + (BACKEND_TIMEOUT_MS / 1000)
                    + "s — dropping its members from local GUI");
            List<UUID[]> toRemove = new ArrayList<>();
            for (Map.Entry<UUID, Map<UUID, RemoteMember>> g : remoteByGroup.entrySet()) {
                for (Map.Entry<UUID, RemoteMember> m : g.getValue().entrySet()) {
                    if (backend.equals(m.getValue().backend())) {
                        toRemove.add(new UUID[]{g.getKey(), m.getKey()});
                    }
                }
            }
            for (UUID[] pair : toRemove) {
                Map<UUID, RemoteMember> g = remoteByGroup.get(pair[0]);
                if (g == null) continue;
                RemoteMember removed = g.remove(pair[1]);
                if (g.isEmpty()) remoteByGroup.remove(pair[0]);
                if (removed != null) {
                    final UUID groupId = pair[0];
                    Bukkit.getScheduler().runTask(plugin,
                            () -> removeMemberFromLocalListeners(groupId, removed));
                }
            }
            lastSeenAt.remove(backend);
        }
    }

    /* ----------------------- Outbound (catch-up for new local joiners) --- */

    /**
     * When a local player joins a global group, send them the existing
     * remote roster so the GUI is correct immediately.
     */
    void catchUpNewLocalJoiner(UUID groupId, Player joiner) {
        Map<UUID, RemoteMember> members = remoteByGroup.get(groupId);
        if (members == null || members.isEmpty()) return;
        for (RemoteMember m : members.values()) {
            sendMemberTo(joiner, groupId, m);
        }
    }

    /* ----------------------- Internals ----------------------- */

    private void pushMemberToLocalListeners(UUID groupId, RemoteMember member) {
        for (UUID localId : membership.getLocalMembers(groupId)) {
            Player p = Bukkit.getPlayer(localId);
            if (p != null && p.isOnline()) {
                sendMemberTo(p, groupId, member);
            }
        }
    }

    private void removeMemberFromLocalListeners(UUID groupId, RemoteMember member) {
        for (UUID localId : membership.getLocalMembers(groupId)) {
            Player p = Bukkit.getPlayer(localId);
            if (p != null && p.isOnline()) {
                svcPackets.sendRemove(p, member.uuid());
                skinSyncer.removeRemotePlayer(p, member.uuid());
            }
        }
    }

    private void sendMemberTo(Player recipient, UUID groupId, RemoteMember member) {
        skinSyncer.addRemotePlayer(recipient, member.profile());
        svcPackets.sendState(recipient, member.uuid(), member.name(), groupId);
    }

    /** Tear down everything we've sent to local clients (called on plugin shutdown). */
    void shutdown() {
        for (Map.Entry<UUID, Map<UUID, RemoteMember>> entry : remoteByGroup.entrySet()) {
            for (RemoteMember m : entry.getValue().values()) {
                removeMemberFromLocalListeners(entry.getKey(), m);
            }
        }
        remoteByGroup.clear();
        lastSeenAt.clear();
    }

    record RemoteMember(UUID uuid, String name, ProfileCodec.Snapshot profile, String backend) {
        public RemoteMember {
            Objects.requireNonNull(uuid);
            Objects.requireNonNull(profile);
            Objects.requireNonNull(backend);
        }
    }
}
