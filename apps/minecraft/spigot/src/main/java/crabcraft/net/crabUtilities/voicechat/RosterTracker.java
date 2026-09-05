package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
    private final String thisBackend;
    private final Logger logger;
    private final Consumer<UUID> invalidateSpeaker;
    private volatile boolean closed;

    /** groupId -> playerId -> remote member metadata. */
    private final Map<UUID, Map<UUID, RemoteMember>> remoteByGroup = new ConcurrentHashMap<>();

    RosterTracker(CrabUtilities plugin, SvcPacketSender svcPackets,
                  String thisBackend, Logger logger, Consumer<UUID> invalidateSpeaker) {
        this.plugin = plugin;
        this.svcPackets = svcPackets;
        this.thisBackend = thisBackend;
        this.logger = logger;
        this.invalidateSpeaker = invalidateSpeaker;
    }

    /* ----------------------- Inbound from Redis ----------------------- */

    void onLifecycleMessage(String message) {
        if (closed || message == null || message.isEmpty()) return;
        // Mutate the roster and send its client packets in the same ordered
        // task. Otherwise a queued removal can erase a newer join or catch-up.
        Bukkit.getScheduler().runTask(plugin, () -> applyLifecycleMessage(message));
    }

    private void applyLifecycleMessage(String message) {
        if (closed) return;
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
        if (Bukkit.getPlayer(join.playerId()) != null) {
            onLocalConnect(join.playerId());
            return;
        }

        long now = System.currentTimeMillis();
        // A player can move directly between groups. Treat each accepted join
        // as their single authoritative roster location.
        for (Map.Entry<UUID, Map<UUID, RemoteMember>> entry : remoteByGroup.entrySet()) {
            if (!entry.getKey().equals(join.groupId())) {
                if (entry.getValue().remove(join.playerId()) != null) {
                    invalidateSpeaker.accept(join.playerId());
                }
                if (entry.getValue().isEmpty()) remoteByGroup.remove(entry.getKey());
            }
        }
        Map<UUID, RemoteMember> groupMap = remoteByGroup
                .computeIfAbsent(join.groupId(), k -> new ConcurrentHashMap<>());
        RemoteMember existing = groupMap.get(join.playerId());

        if (existing != null
                && existing.route().equals(join.route())
                && Objects.equals(existing.name(), join.name())) {
            // Routine 30 s re-broadcast for an entry we already track —
            // just refresh the timestamp, no client packet needed.
            groupMap.put(join.playerId(), existing.withTimestamp(now));
            return;
        }

        RemoteMember member = new RemoteMember(join.playerId(), join.name(),
                join.route(), now);
        groupMap.put(join.playerId(), member);
        if (existing != null && !existing.route().equals(member.route())) {
            invalidateSpeaker.accept(join.playerId());
        }
        logger.info("Roster join: " + join.name() + " (" + join.playerId()
                + ") in group " + join.groupId() + " from backend '" + join.backend() + "'");

        pushMemberToLocalListeners(join.groupId(), member);
    }

    private void handleLeave(String message) {
        VoiceMessages.RosterLeave leave = VoiceMessages.decodeRosterLeave(message);
        if (leave == null) return;
        if (thisBackend.equals(leave.backend())) return;

        Map<UUID, RemoteMember> members = remoteByGroup.get(leave.groupId());
        if (members == null) return;
        // Only the exact route that owns the tracked entry may remove it. On a
        // server hop the origin's ROSTER_LEAVE can be delayed (async publish
        // under tick lag) and arrive after the destination's ROSTER_JOIN;
        // honoring it here would evict a member who is still in the group.
        RemoteMember removed = members.get(leave.playerId());
        if (removed == null || !removed.route().equals(leave.route())
                || !members.remove(leave.playerId(), removed)) return;
        if (members.isEmpty()) remoteByGroup.remove(leave.groupId());
        invalidateSpeaker.accept(leave.playerId());
        removeMemberFromLocalListeners(removed);
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
        for (Map.Entry<UUID, Map<UUID, RemoteMember>> g : remoteByGroup.entrySet()) {
            for (Map.Entry<UUID, RemoteMember> m : g.getValue().entrySet()) {
                RemoteMember removed = m.getValue();
                if (now - removed.lastSeenAt() <= ENTRY_TIMEOUT_MS
                        || !g.getValue().remove(m.getKey(), removed)) continue;
                UUID groupId = g.getKey();
                logger.info("Roster sweep: dropping stale " + removed.name()
                        + " (" + removed.uuid() + ") from group " + groupId
                        + " — no re-broadcast from backend '" + removed.backend()
                        + "' in " + (ENTRY_TIMEOUT_MS / 1000) + "s");
                invalidateSpeaker.accept(removed.uuid());
                removeMemberFromLocalListeners(removed);
            }
            if (g.getValue().isEmpty()) remoteByGroup.remove(g.getKey());
        }
    }

    /** Native SVC now owns this player; discard their previous remote entry. */
    void onLocalConnect(UUID playerId) {
        for (Map.Entry<UUID, Map<UUID, RemoteMember>> entry : remoteByGroup.entrySet()) {
            entry.getValue().remove(playerId);
            if (entry.getValue().isEmpty()) remoteByGroup.remove(entry.getKey());
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

    private void removeMemberFromLocalListeners(RemoteMember member) {
        // Never strip state for a player who is live on this backend: that
        // happens when a cross-server member hops HERE and their old
        // backend's ROSTER_LEAVE lands after they connected. SVC's native
        // PlayerState is authoritative for local players, and a fake
        // remove_state would wipe it on every client with no repair path.
        if (Bukkit.getPlayer(member.uuid()) != null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            svcPackets.sendRemove(p, member.uuid());
        }
    }

    private void sendMemberTo(Player recipient, UUID groupId, RemoteMember member) {
        // Same rule as removal: never fake state for a live local player.
        if (Bukkit.getPlayer(member.uuid()) != null) return;
        svcPackets.sendState(recipient, member.uuid(), member.name(), groupId);
    }

    /** Tear down everything we've sent to local clients (called on plugin shutdown). */
    void shutdown() {
        closed = true;
        for (Map.Entry<UUID, Map<UUID, RemoteMember>> entry : remoteByGroup.entrySet()) {
            for (RemoteMember m : entry.getValue().values()) {
                removeMemberFromLocalListeners(m);
            }
        }
        remoteByGroup.clear();
    }

    record RemoteMember(UUID uuid, String name, String route, long lastSeenAt) {
        public RemoteMember {
            Objects.requireNonNull(uuid);
            Objects.requireNonNull(route);
        }

        String backend() { return VoiceMessages.routeBackend(route); }

        RemoteMember withTimestamp(long seenTimestamp) {
            return new RemoteMember(uuid, name, route, seenTimestamp);
        }
    }
}
