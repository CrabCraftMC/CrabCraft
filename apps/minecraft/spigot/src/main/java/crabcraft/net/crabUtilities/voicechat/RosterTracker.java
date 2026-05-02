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
 * changes, drives {@link SvcPacketSender} to update the relevant
 * local clients so cross-server members appear in the SVC GUI roster.
 *
 * <p>Skin rendering relies on the existing tab-list sync (a separate
 * plugin) populating each receiving client's {@code playerInfoMap}.
 * SVC's {@code GameProfileUtils.getSkin} reads from there and renders
 * the real skin; a missing entry falls back to the default Steve/Alex.
 *
 * <p>Each entry carries a {@code lastSeenAt} timestamp updated on
 * every {@code ROSTER_JOIN} (the publisher re-broadcasts every 30 s).
 * A periodic sweep drops entries that haven't been refreshed in 90 s,
 * which catches the case where a player's home backend crashed or
 * shut down without firing a clean {@code ROSTER_LEAVE}.
 */
class RosterTracker {

    /** Drop entries that haven't been re-broadcast within this window. */
    static final long ENTRY_TIMEOUT_MS = 90_000L;

    private final CrabUtilities plugin;
    private final SvcPacketSender svcPackets;
    private final Set<UUID> crossServerGroupIds;
    private final String thisBackend;
    private final Logger logger;

    /** groupId -> playerId -> remote member metadata. */
    private final Map<UUID, Map<UUID, RemoteMember>> remoteByGroup = new ConcurrentHashMap<>();

    RosterTracker(CrabUtilities plugin, SvcPacketSender svcPackets,
                  Set<UUID> crossServerGroupIds,
                  String thisBackend, Logger logger) {
        this.plugin = plugin;
        this.svcPackets = svcPackets;
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
            default -> {}
        }
    }

    private void handleJoin(String message) {
        VoiceMessages.RosterJoin join = VoiceMessages.decodeRosterJoin(message);
        if (join == null) return;
        if (thisBackend.equals(join.backend())) return;
        if (!crossServerGroupIds.contains(join.groupId())) return;

        long now = System.currentTimeMillis();
        Map<UUID, RemoteMember> groupMap = remoteByGroup
                .computeIfAbsent(join.groupId(), k -> new ConcurrentHashMap<>());
        RemoteMember existing = groupMap.get(join.playerId());

        if (existing != null
                && existing.backend().equals(join.backend())
                && Objects.equals(existing.name(), join.name())) {
            // Routine 30 s re-broadcast for an entry we already track —
            // just refresh the timestamp, no client packet needed.
            groupMap.put(join.playerId(), existing.withTimestamp(now));
            return;
        }

        RemoteMember member = new RemoteMember(join.playerId(), join.name(),
                join.backend(), now);
        groupMap.put(join.playerId(), member);
        logger.info("Roster join: " + join.name() + " (" + join.playerId()
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

    /**
     * Drops entries whose last {@code ROSTER_JOIN} re-broadcast was
     * more than {@link #ENTRY_TIMEOUT_MS} ago. This catches the case
     * where a backend crashed or restarted without firing
     * {@code ROSTER_LEAVE}, leaving "ghost" members in the local GUI
     * showing as a default Steve head (because the offline player is
     * also gone from {@code playerInfoMap}).
     */
    void sweepStaleEntries() {
        long now = System.currentTimeMillis();
        List<UUID[]> toRemove = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, RemoteMember>> g : remoteByGroup.entrySet()) {
            for (Map.Entry<UUID, RemoteMember> m : g.getValue().entrySet()) {
                if (now - m.getValue().lastSeenAt() > ENTRY_TIMEOUT_MS) {
                    toRemove.add(new UUID[]{g.getKey(), m.getKey()});
                }
            }
        }
        if (toRemove.isEmpty()) return;
        for (UUID[] pair : toRemove) {
            Map<UUID, RemoteMember> g = remoteByGroup.get(pair[0]);
            if (g == null) continue;
            RemoteMember removed = g.remove(pair[1]);
            if (g.isEmpty()) remoteByGroup.remove(pair[0]);
            if (removed != null) {
                final UUID groupId = pair[0];
                logger.info("Roster sweep: dropping stale " + removed.name()
                        + " (" + removed.uuid() + ") from group " + groupId
                        + " — no re-broadcast from backend '" + removed.backend()
                        + "' in " + (ENTRY_TIMEOUT_MS / 1000) + "s");
                Bukkit.getScheduler().runTask(plugin,
                        () -> removeMemberFromLocalListeners(groupId, removed));
            }
        }
    }

    /* ----------------------- Outbound (catch-up for new local joiners) --- */

    /**
     * When a local player joins a global group, send them the existing
     * remote roster so the GUI is correct immediately. (Mostly redundant
     * now that {@link #pushMemberToLocalListeners} broadcasts to every
     * online player, but kept for tight in-group handoff with no
     * scheduler-tick race.)
     */
    void catchUpNewLocalJoiner(UUID groupId, Player joiner) {
        Map<UUID, RemoteMember> members = remoteByGroup.get(groupId);
        if (members == null || members.isEmpty()) return;
        for (RemoteMember m : members.values()) {
            sendMemberTo(joiner, groupId, m);
        }
    }

    /**
     * When a local player's voice connection comes up, send them the
     * full current cross-server roster across every group. This is what
     * makes the join-group GUI show cross-server members BEFORE the
     * player has joined any group.
     */
    void catchUpNewLocalConnection(Player joiner) {
        for (Map.Entry<UUID, Map<UUID, RemoteMember>> entry : remoteByGroup.entrySet()) {
            for (RemoteMember m : entry.getValue().values()) {
                sendMemberTo(joiner, entry.getKey(), m);
            }
        }
    }

    /* ----------------------- Internals ----------------------- */

    private void pushMemberToLocalListeners(UUID groupId, RemoteMember member) {
        // Broadcast to every online player on this backend, not just
        // group members — otherwise a player who hasn't yet joined any
        // cross-server group has no PlayerState entries for cross-
        // server members and the join-group GUI shows them as empty.
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendMemberTo(p, groupId, member);
        }
    }

    private void removeMemberFromLocalListeners(UUID groupId, RemoteMember member) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            svcPackets.sendRemove(p, member.uuid());
        }
    }

    private void sendMemberTo(Player recipient, UUID groupId, RemoteMember member) {
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
    }

    record RemoteMember(UUID uuid, String name, String backend, long lastSeenAt) {
        public RemoteMember {
            Objects.requireNonNull(uuid);
            Objects.requireNonNull(backend);
        }

        RemoteMember withTimestamp(long ts) {
            return new RemoteMember(uuid, name, backend, ts);
        }
    }
}
