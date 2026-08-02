package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Applies accepted call targets from their durable Redis records.
 *
 * <p>{@code CALL_JOIN} is only a wake-up hint. Every join fetches the current
 * target generation and the password-bearing group definition from Redis, then
 * verifies the local voice session, route and intent again on the server thread.
 */
final class CallTargetSynchronizer implements AutoCloseable {
    static final String CALL_GROUP_NAME = "Private Call";

    private final CrabUtilities plugin;
    private final VoicechatServerApi api;
    private final RedisVoiceBus bus;
    private final GroupSynchronizer groups;
    private final String backend;
    private final Function<UUID, Long> sessionLookup;
    private final Function<UUID, String> routeLookup;
    private final Consumer<UUID> cancelRestore;
    private final Consumer<UUID> reconcileMembership;
    private final Logger logger;

    private final Map<UUID, Long> requestGenerations = new ConcurrentHashMap<>();
    private final Map<UUID, VoiceMessages.CallTarget> activeTargets = new ConcurrentHashMap<>();
    private final Map<UUID, VoiceMessages.CallTarget> suppressedTargets = new ConcurrentHashMap<>();
    private final Map<UUID, VoiceMessages.CallTarget> hintedTargets = new ConcurrentHashMap<>();
    private final Set<UUID> manualOverrides = ConcurrentHashMap.newKeySet();
    private final Set<UUID> applying = ConcurrentHashMap.newKeySet();
    private final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CrabUtilities-CallTargets");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;

    CallTargetSynchronizer(CrabUtilities plugin, VoicechatServerApi api, RedisVoiceBus bus,
                           GroupSynchronizer groups, String backend,
                           Function<UUID, Long> sessionLookup,
                           Function<UUID, String> routeLookup,
                           Consumer<UUID> cancelRestore,
                           Consumer<UUID> reconcileMembership,
                           Logger logger) {
        this.plugin = plugin;
        this.api = api;
        this.bus = bus;
        this.groups = groups;
        this.backend = backend;
        this.sessionLookup = sessionLookup;
        this.routeLookup = routeLookup;
        this.cancelRestore = cancelRestore;
        this.reconcileMembership = reconcileMembership;
        this.logger = logger;
    }

    void onJoinHint(VoiceMessages.CallJoin join) {
        if (closed || sessionLookup.apply(join.playerId()) == null) return;
        hintedTargets.put(join.playerId(), join.target());
        request(Set.of(join.playerId()));
    }

    void reconcile(Set<UUID> playerIds) {
        if (!closed && !playerIds.isEmpty()) request(playerIds);
    }

    void onConnect(UUID playerId) {
        invalidateRequests(playerId);
        activeTargets.remove(playerId);
        suppressedTargets.remove(playerId);
        hintedTargets.remove(playerId);
        manualOverrides.remove(playerId);
    }

    void onRouteReady(UUID playerId) {
        if (!closed && sessionLookup.apply(playerId) != null) request(Set.of(playerId));
    }

    boolean isApplying(UUID playerId) {
        return applying.contains(playerId);
    }

    /** Prevents a missed or delayed join from undoing an explicit local group choice. */
    void onManualGroupChange(UUID playerId) {
        if (closed) return;
        invalidateRequests(playerId);
        manualOverrides.add(playerId);
        VoiceMessages.CallTarget hinted = hintedTargets.remove(playerId);
        VoiceMessages.CallTarget active = activeTargets.remove(playerId);
        VoiceMessages.CallTarget target = manualSuppressionTarget(active, hinted);
        if (target != null) {
            suppressedTargets.put(playerId, target);
            clearTarget(playerId, target, true);
        }
        request(Set.of(playerId));
    }

    void onMembershipReconciled(UUID playerId, UUID groupId) {
        VoiceMessages.CallTarget target = activeTargets.get(playerId);
        if (target == null) return;
        if (!target.groupId().equals(groupId)) {
            onManualGroupChange(playerId);
            return;
        }
        // The target is only a durable pending-join intent. Membership and
        // backend-hop recovery are owned by the refreshed player-group lease.
        clearTarget(playerId, target, false);
    }

    void onDisconnect(UUID playerId) {
        invalidateRequests(playerId);
        activeTargets.remove(playerId);
        suppressedTargets.remove(playerId);
        hintedTargets.remove(playerId);
        manualOverrides.remove(playerId);
        applying.remove(playerId);
    }

    private void request(Set<UUID> playerIds) {
        if (closed) return;
        Map<UUID, Request> requests = new HashMap<>();
        for (UUID playerId : playerIds) {
            Long session = sessionLookup.apply(playerId);
            if (session == null) continue;
            long generation = requestGenerations.merge(playerId, 1L, Long::sum);
            requests.put(playerId, new Request(session, generation));
        }
        if (requests.isEmpty()) return;

        try {
            recoveryExecutor.execute(() -> read(requests));
        } catch (RejectedExecutionException ignored) {
            // Plugin is stopping.
        }
    }

    private void read(Map<UUID, Request> requests) {
        if (closed) return;
        RedisVoiceBus.ReadResult<Map<UUID, VoiceMessages.CallTarget>> read =
                bus.fetchCallTargets(requests.keySet());
        if (!read.succeeded()) return;

        Map<UUID, VoiceMessages.GroupDefinition> definitions = bus.fetchGroups();
        if (definitions == null) return;
        try {
            Bukkit.getScheduler().runTask(plugin,
                    () -> applyRead(requests, read.value(), definitions));
        } catch (Exception ignored) {
            // Plugin is stopping.
        }
    }

    private void applyRead(Map<UUID, Request> requests,
                           Map<UUID, VoiceMessages.CallTarget> targets,
                           Map<UUID, VoiceMessages.GroupDefinition> definitions) {
        if (closed) return;
        for (Map.Entry<UUID, Request> entry : requests.entrySet()) {
            UUID playerId = entry.getKey();
            Request request = entry.getValue();
            VoiceMessages.CallTarget target = targets.get(playerId);
            if (!isCurrent(playerId, request)) continue;

            if (manualOverrides.remove(playerId)) {
                activeTargets.remove(playerId);
                VoiceMessages.CallTarget hinted = hintedTargets.get(playerId);
                VoiceMessages.CallTarget suppressed = suppressedTargets.get(playerId);
                if (isNewAcceptedTarget(target, hinted, suppressed)) {
                    // This exact generation was accepted after the manual
                    // choice; only a matching authoritative target may win.
                } else if (target != null) {
                    suppressedTargets.put(playerId, target);
                    clearTarget(playerId, target, true);
                    continue;
                } else {
                    suppressedTargets.remove(playerId);
                    hintedTargets.remove(playerId);
                    continue;
                }
            }
            if (target == null) {
                activeTargets.remove(playerId);
                suppressedTargets.remove(playerId);
                hintedTargets.remove(playerId);
                continue;
            }

            VoiceMessages.CallTarget suppressed = suppressedTargets.get(playerId);
            if (target.equals(suppressed)) {
                clearTarget(playerId, target, true);
                continue;
            }
            if (suppressed != null) suppressedTargets.remove(playerId, suppressed);

            VoiceMessages.GroupDefinition definition = definitions.get(target.groupId());
            if (!isCallGroup(definition)) continue;
            applyTarget(playerId, request, target, definition);
        }
    }

    private void applyTarget(UUID playerId, Request request,
                             VoiceMessages.CallTarget target,
                             VoiceMessages.GroupDefinition definition) {
        if (!isCurrent(playerId, request)) return;
        Player player = Bukkit.getPlayer(playerId);
        VoicechatConnection connection = api.getConnectionOf(playerId);
        String route = routeLookup.apply(playerId);
        if (player == null || !player.isOnline() || connection == null
                || !connection.isInstalled() || !connection.isConnected()
                || !backend.equals(VoiceMessages.routeBackend(route))) return;

        Group current = connection.getGroup();
        if (current != null && target.groupId().equals(current.getId())) {
            confirm(playerId, request, target);
            return;
        }

        Group callGroup = groups.findLocal(target.groupId());
        if (callGroup == null) callGroup = groups.apply(definition);
        if (callGroup == null || !isCurrent(playerId, request)) return;

        cancelRestore.accept(playerId);
        applying.add(playerId);
        try {
            connection.setGroup(callGroup);
        } catch (Exception e) {
            logger.warning("Could not connect " + playerId + " to a private voice call");
            return;
        } finally {
            applying.remove(playerId);
        }
        try {
            Bukkit.getScheduler().runTask(plugin,
                    () -> confirm(playerId, request, target));
        } catch (Exception ignored) {
            // Plugin is stopping.
        }
    }

    private void confirm(UUID playerId, Request request, VoiceMessages.CallTarget target) {
        if (!isCurrent(playerId, request)) return;
        VoicechatConnection connection = api.getConnectionOf(playerId);
        Group group = connection == null ? null : connection.getGroup();
        if (connection == null || !connection.isConnected()
                || !backend.equals(VoiceMessages.routeBackend(routeLookup.apply(playerId)))
                || group == null || !target.groupId().equals(group.getId())) return;
        activeTargets.put(playerId, target);
        hintedTargets.remove(playerId, target);
        reconcileMembership.accept(playerId);
    }

    private boolean isCurrent(UUID playerId, Request request) {
        return !closed
                && sameSession(playerId, request)
                && Objects.equals(requestGenerations.get(playerId), request.generation());
    }

    private boolean sameSession(UUID playerId, Request request) {
        return Objects.equals(sessionLookup.apply(playerId), request.session());
    }

    private void clearTarget(UUID playerId, VoiceMessages.CallTarget target,
                             boolean reconcileAfter) {
        bus.clearCallTarget(playerId, target, routeLookup.apply(playerId),
                reconcileAfter ? () -> reconcileMembership.accept(playerId) : () -> {});
    }

    private void invalidateRequests(UUID playerId) {
        requestGenerations.merge(playerId, 1L, Long::sum);
    }

    static boolean isCallGroup(VoiceMessages.GroupDefinition definition) {
        return definition != null
                && CALL_GROUP_NAME.equals(definition.name())
                && definition.password() != null
                && !definition.password().isBlank()
                && definition.type() == Group.Type.OPEN
                && definition.hidden()
                && !definition.permanent();
    }

    static boolean isNewAcceptedTarget(VoiceMessages.CallTarget target,
                                       VoiceMessages.CallTarget hinted,
                                       VoiceMessages.CallTarget suppressed) {
        return target != null && target.equals(hinted) && !target.equals(suppressed);
    }

    static VoiceMessages.CallTarget manualSuppressionTarget(
            VoiceMessages.CallTarget active, VoiceMessages.CallTarget hinted) {
        return hinted != null ? hinted : active;
    }

    @Override
    public void close() {
        closed = true;
        recoveryExecutor.shutdownNow();
        requestGenerations.clear();
        activeTargets.clear();
        suppressedTargets.clear();
        hintedTargets.clear();
        manualOverrides.clear();
        applying.clear();
    }

    private record Request(long session, long generation) {}
}
