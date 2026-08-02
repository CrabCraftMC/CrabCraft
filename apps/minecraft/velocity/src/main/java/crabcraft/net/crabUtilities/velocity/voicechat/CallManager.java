package crabcraft.net.crabUtilities.velocity.voicechat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns network-wide call invitations and sends trusted join requests to the backends. */
public final class CallManager {

    static final String CONTROL_CHANNEL = "crabcraft:svc:roster";
    static final String OP_CALL_JOIN = "CALL_JOIN";
    static final String OP_CALL_RING_START = "CALL_RING_START";
    static final String OP_CALL_RING_STOP = "CALL_RING_STOP";
    static final String CALL_KEY_PREFIX = "crabcraft:svc:call:";
    static final String PLAYER_GROUP_KEY_PREFIX = "crabcraft:svc:player-group:";
    static final String CALL_TARGET_KEY_PREFIX = "crabcraft:svc:call-target:";
    static final String SEPARATOR = "\0";

    private static final String ACCEPT_CALL_SCRIPT = """
            if redis.call('get', KEYS[1]) ~= ARGV[1] or redis.call('get', KEYS[2]) ~= ARGV[2] then return 0 end
            redis.call('setex', KEYS[3], ARGV[3], ARGV[4])
            redis.call('setex', KEYS[4], ARGV[5], ARGV[6])
            redis.call('setex', KEYS[5], ARGV[5], ARGV[6])
            redis.call('setex', KEYS[6], ARGV[5], ARGV[6])
            redis.call('setex', KEYS[7], ARGV[5], ARGV[6])
            redis.call('publish', ARGV[7], ARGV[8])
            redis.call('publish', ARGV[7], ARGV[9])
            return 1
            """;
    private static final String RETRY_CALL_RING_STOP_SCRIPT = """
            local current = redis.call('TIME')
            local nowMillis = (tonumber(current[1]) * 1000) + math.floor(tonumber(current[2]) / 1000)
            if nowMillis >= tonumber(ARGV[4]) then return 0 end
            redis.call('publish', ARGV[1], ARGV[2])
            redis.call('publish', ARGV[1], ARGV[3])
            return 1
            """;

    static final long INVITE_TIMEOUT_MILLIS = 30_000L;
    private static final long INVITE_COOLDOWN_MILLIS = 2_000L;
    private static final int MAXIMUM_OUTGOING_INVITES = 5;
    private static final long CALL_TTL_SECONDS = 180L;
    private static final long PLAYER_GROUP_TTL_SECONDS = 90L;
    private static final Duration RING_START_RETRY_INTERVAL = Duration.ofSeconds(2L);
    private static final Duration RING_STOP_RETRY_INTERVAL = Duration.ofSeconds(2L);

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private final CallInviteRegistry invites = new CallInviteRegistry();
    private final java.util.Map<String, InviteTasks> tasksByToken =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, StopRetry> stopRetriesByToken =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> lastInviteAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ThreadPoolExecutor worker;
    private final Object acceptanceLifecycleLock = new Object();

    private volatile JedisPool jedisPool;
    private volatile boolean running;

    public CallManager(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(128),
                runnable -> {
                    Thread thread = new Thread(runnable, "CrabUtilities-Calls");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxWait(Duration.ofMillis(1500L));
        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000);
        }
        running = true;
        plugin.getLogger().info("Voice call manager started.");
    }

    public void invite(Player caller, Player target) {
        if (caller.getUniqueId().equals(target.getUniqueId())) {
            error(caller, "You can't call yourself.");
            return;
        }
        if (!reserveInviteSlot(caller.getUniqueId(), System.currentTimeMillis())) {
            error(caller, "Please wait a moment before calling someone else.");
            return;
        }
        submit(caller, () -> createInvite(caller.getUniqueId(), caller.getUsername(),
                target.getUniqueId(), target.getUsername()));
    }

    public void accept(Player target, String token) {
        submit(target, () -> acceptInvite(target.getUniqueId(), token));
    }

    public void decline(Player target, String token) {
        submit(target, () -> declineInvite(target.getUniqueId(), token));
    }

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        lastInviteAt.remove(playerId);
        Collection<CallInviteRegistry.Invite> removed;
        synchronized (acceptanceLifecycleLock) {
            removed = invites.removePlayer(playerId);
            for (CallInviteRegistry.Invite invite : removed) {
                registerRingtoneStopRetriesLocked(invite);
            }
        }
        for (CallInviteRegistry.Invite invite : removed) {
            publishRingtoneStops(invite);
            UUID otherId = invite.callerId().equals(playerId)
                    ? invite.targetId() : invite.callerId();
            plugin.getServer().getPlayer(otherId).ifPresent(other ->
                    error(other, event.getPlayer().getUsername() + " went offline, so the call was cancelled."));
        }
    }

    public void shutdown() {
        // Wait for a transaction which already committed to finish its local
        // state/messages before a replacement manager can start on reload.
        Map<String, CallInviteRegistry.Invite> pendingByToken = new LinkedHashMap<>();
        synchronized (acceptanceLifecycleLock) {
            running = false;
            for (CallInviteRegistry.Invite invite : invites.clear()) {
                pendingByToken.put(invite.token(), invite);
            }
            for (InviteTasks tasks : new ArrayList<>(tasksByToken.values())) {
                pendingByToken.put(tasks.invite().token(), tasks.invite());
                tasks.cancel();
            }
            tasksByToken.clear();
            for (StopRetry retry : new ArrayList<>(stopRetriesByToken.values())) {
                pendingByToken.put(retry.invite().token(), retry.invite());
                retry.cancel();
            }
            stopRetriesByToken.clear();
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(3L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        JedisPool pool = jedisPool;
        if (pool != null && !pool.isClosed() && !pendingByToken.isEmpty()) {
            try (Jedis jedis = pool.getResource()) {
                for (CallInviteRegistry.Invite invite : pendingByToken.values()) {
                    publishRingtoneStops(jedis, invite);
                }
            } catch (Exception e) {
                plugin.getLogger().warn("Could not stop voice call ringtones during shutdown", e);
            }
        }
        jedisPool = null;
        if (pool != null && !pool.isClosed()) {
            try {
                pool.close();
            } catch (NoClassDefFoundError ignored) {
            }
        }
        lastInviteAt.clear();
    }

    private void createInvite(UUID callerId, String callerName, UUID targetId, String targetName) {
        if (!running) return;
        Optional<Player> currentCaller = plugin.getServer().getPlayer(callerId);
        Optional<Player> currentTarget = plugin.getServer().getPlayer(targetId);
        if (currentCaller.isEmpty() || currentTarget.isEmpty()) {
            currentCaller.ifPresent(player -> error(player, "That player is no longer online."));
            return;
        }
        String callerSession = plugin.getVoiceSessionToken(callerId);
        String targetSession = plugin.getVoiceSessionToken(targetId);
        if (callerSession == null || targetSession == null) {
            error(currentCaller.get(), "That player is no longer online.");
            return;
        }

        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) {
            error(currentCaller.get(), "Voice calls are not available right now.");
            return;
        }

        long now = System.currentTimeMillis();
        if (!invites.hasOutgoingCapacity(callerId, MAXIMUM_OUTGOING_INVITES, now)) {
            error(currentCaller.get(), "Wait for one of your current calls to be answered first.");
            return;
        }
        CallInviteRegistry.Invite ringingInvite = null;
        try (Jedis jedis = pool.getResource()) {
            CallInviteRegistry.CallCredentials targetCall = activeCall(jedis, targetId, now);
            if (targetCall != null) {
                error(currentCaller.get(), targetCall.groupId().equals(
                        Optional.ofNullable(activeCall(jedis, callerId, now))
                                .map(CallInviteRegistry.CallCredentials::groupId)
                                .orElse(null))
                        ? targetName + " is already in your call."
                        : targetName + " is already in another call.");
                return;
            }

            CallInviteRegistry.CallCredentials active = activeCall(jedis, callerId, now);
            boolean callerWasInCall = active != null;
            CallInviteRegistry.CallCredentials call = active != null
                    ? active
                    : invites.provisionalFor(callerId, now, () -> new CallInviteRegistry.CallCredentials(
                            UUID.randomUUID(), randomSecret(24),
                            now + TimeUnit.SECONDS.toMillis(CALL_TTL_SECONDS)));

            jedis.setex(callKey(call.groupId()), CALL_TTL_SECONDS, call.password());

            long ringingStartedAt = System.currentTimeMillis();
            String token = randomSecret(18);
            CallInviteRegistry.Invite invite = new CallInviteRegistry.Invite(
                    token, callerId, callerName, targetId, targetName,
                    call, callerWasInCall, ringingStartedAt + INVITE_TIMEOUT_MILLIS);
            synchronized (acceptanceLifecycleLock) {
                if (!running) return;
                Player liveCaller = plugin.getServer().getPlayer(callerId).orElse(null);
                Player liveTarget = plugin.getServer().getPlayer(targetId).orElse(null);
                if (liveCaller != currentCaller.get() || liveTarget != currentTarget.get()
                        || !plugin.isVoiceSessionCurrent(callerId, callerSession)
                        || !plugin.isVoiceSessionCurrent(targetId, targetSession)) {
                    if (liveCaller != null) {
                        error(liveCaller, "That player is no longer online.");
                    }
                    return;
                }
                if (!invites.add(invite, ringingStartedAt)) {
                    error(liveCaller, targetName + " already has an incoming call.");
                    return;
                }
                ringingInvite = invite;
                try {
                    beginRinging(invite, liveCaller, liveTarget, jedis);
                } catch (Exception e) {
                    invites.remove(invite.token(), invite.targetId());
                    registerRingtoneStopRetriesLocked(invite);
                    throw e;
                }
            }
        } catch (Exception e) {
            if (ringingInvite != null) {
                synchronized (acceptanceLifecycleLock) {
                    invites.remove(ringingInvite.token(), ringingInvite.targetId());
                    registerRingtoneStopRetriesLocked(ringingInvite);
                }
                publishRingtoneStops(ringingInvite);
            }
            if (!running) return;
            plugin.getLogger().warn("Could not create voice call invitation", e);
            error(currentCaller.get(), "Voice calls are not available right now.");
        }
    }

    private void acceptInvite(UUID targetId, String token) {
        long now = System.currentTimeMillis();
        Optional<CallInviteRegistry.Invite> taken;
        CallInviteRegistry.Invite expiredRinging = null;
        synchronized (acceptanceLifecycleLock) {
            if (!running) return;
            taken = invites.take(token, targetId, now);
            if (taken.isPresent()) {
                registerRingtoneStopRetriesLocked(taken.get());
            } else {
                expiredRinging = cancelInviteTasksForTarget(token, targetId);
                if (expiredRinging != null) {
                    registerRingtoneStopRetriesLocked(expiredRinging);
                }
            }
        }
        if (taken.isEmpty()) {
            if (expiredRinging != null) publishRingtoneStops(expiredRinging);
            plugin.getServer().getPlayer(targetId).ifPresent(player ->
                    error(player, "That call invitation is no longer valid."));
            return;
        }

        CallInviteRegistry.Invite invite = taken.get();
        publishRingtoneStops(invite);
        Optional<Player> caller = plugin.getServer().getPlayer(invite.callerId());
        Optional<Player> target = plugin.getServer().getPlayer(targetId);
        if (caller.isEmpty() || target.isEmpty()) {
            target.ifPresent(player -> error(player, "The caller is no longer online."));
            return;
        }

        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) {
            error(target.get(), "Voice calls are not available right now.");
            return;
        }

        try (Jedis jedis = pool.getResource()) {
            CallInviteRegistry.CallCredentials callerCall = activeCall(jedis, invite.callerId(), now);
            if ((callerCall != null && !callerCall.groupId().equals(invite.call().groupId()))
                    || (invite.callerWasInCall() && callerCall == null)) {
                error(target.get(), "The caller is no longer in that call.");
                return;
            }
            CallInviteRegistry.CallCredentials targetCall = activeCall(jedis, targetId, now);
            if (targetCall != null && !targetCall.groupId().equals(invite.call().groupId())) {
                error(target.get(), "You are already in another call.");
                return;
            }

            String storedPassword = jedis.get(callKey(invite.call().groupId()));
            if (storedPassword != null && !storedPassword.equals(invite.call().password())) {
                error(target.get(), "That call is no longer available.");
                return;
            }
            String callerSession = plugin.getVoiceSessionToken(invite.callerId());
            String targetSession = plugin.getVoiceSessionToken(targetId);
            if (callerSession == null || targetSession == null) {
                error(target.get(), "One of you disconnected; please try the call again.");
                return;
            }

            String groupId = invite.call().groupId().toString();
            synchronized (acceptanceLifecycleLock) {
                if (!running) return;
                if (!plugin.isVoiceSessionCurrent(invite.callerId(), callerSession)
                        || !plugin.isVoiceSessionCurrent(targetId, targetSession)) {
                    error(target.get(), "One of you disconnected; please try the call again.");
                    return;
                }
                Object result = jedis.eval(ACCEPT_CALL_SCRIPT,
                        java.util.List.of(
                                PlayerLocationTracker.playerSessionKey(invite.callerId()),
                                PlayerLocationTracker.playerSessionKey(targetId),
                                callKey(invite.call().groupId()),
                                playerGroupKey(invite.callerId()),
                                playerGroupKey(targetId),
                                callTargetKey(invite.callerId()),
                                callTargetKey(targetId)),
                        java.util.List.of(
                                callerSession, targetSession,
                                Long.toString(CALL_TTL_SECONDS), invite.call().password(),
                                Long.toString(PLAYER_GROUP_TTL_SECONDS), groupId,
                                CONTROL_CHANNEL,
                                encodeCallJoin(invite.call(), invite.callerId()),
                                encodeCallJoin(invite.call(), targetId)));
                if (!(result instanceof Number) || ((Number) result).longValue() != 1L) {
                    error(target.get(), "One of you disconnected; please try the call again.");
                    error(caller.get(), "The call could not connect because one of you disconnected.");
                    return;
                }
                invites.activate(invite.callerId(), invite.call().groupId());

                target.get().sendMessage(Component.text("Call accepted. Connecting you to ",
                                NamedTextColor.GREEN)
                        .append(Component.text(invite.callerName(), NamedTextColor.AQUA))
                        .append(Component.text("…", NamedTextColor.GREEN)));
                caller.get().sendMessage(Component.text(invite.targetName(), NamedTextColor.AQUA)
                        .append(Component.text(" accepted your call. Connecting… Once connected, anyone in the call can use /call <player> to invite more.",
                                NamedTextColor.GREEN)));
            }
        } catch (Exception e) {
            if (!running) return;
            plugin.getLogger().warn("Could not connect voice call", e);
            error(target.get(), "Voice calls are not available right now.");
            error(caller.get(), "Voice calls are not available right now.");
        }
    }

    private void declineInvite(UUID targetId, String token) {
        Optional<CallInviteRegistry.Invite> taken;
        CallInviteRegistry.Invite expiredRinging = null;
        synchronized (acceptanceLifecycleLock) {
            if (!running) return;
            taken = invites.take(token, targetId, System.currentTimeMillis());
            if (taken.isPresent()) {
                registerRingtoneStopRetriesLocked(taken.get());
            } else {
                expiredRinging = cancelInviteTasksForTarget(token, targetId);
                if (expiredRinging != null) {
                    registerRingtoneStopRetriesLocked(expiredRinging);
                }
            }
        }
        if (taken.isEmpty()) {
            if (expiredRinging != null) publishRingtoneStops(expiredRinging);
            plugin.getServer().getPlayer(targetId).ifPresent(player ->
                    error(player, "That call invitation is no longer valid."));
            return;
        }
        CallInviteRegistry.Invite invite = taken.get();
        publishRingtoneStops(invite);
        plugin.getServer().getPlayer(targetId).ifPresent(player ->
                player.sendMessage(Component.text("Call declined.", NamedTextColor.YELLOW)));
        plugin.getServer().getPlayer(invite.callerId()).ifPresent(player ->
                player.sendMessage(Component.text(invite.targetName(), NamedTextColor.AQUA)
                        .append(Component.text(" declined your call.", NamedTextColor.YELLOW))));
    }

    private void beginRinging(CallInviteRegistry.Invite invite, Player caller, Player target,
                              Jedis jedis) {
        if (!running) return;
        Component accept = Component.text("[ACCEPT]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(acceptCommand(invite.token())))
                .hoverEvent(HoverEvent.showText(Component.text("Join the private voice call")));
        Component decline = Component.text("[DECLINE]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(declineCommand(invite.token())))
                .hoverEvent(HoverEvent.showText(Component.text("Dismiss this call")));
        long remainingMillis = Math.max(0L,
                invite.expiresAtMillis() - System.currentTimeMillis());
        ScheduledTask timeout = null;
        ScheduledTask retry = null;
        try {
            timeout = plugin.getServer().getScheduler()
                    .buildTask(plugin, () -> expireInvite(invite.token()))
                    .delay(Duration.ofMillis(remainingMillis))
                    .schedule();
            retry = plugin.getServer().getScheduler()
                    .buildTask(plugin, () -> retryRingtoneStart(invite.token()))
                    .delay(RING_START_RETRY_INTERVAL)
                    .repeat(RING_START_RETRY_INTERVAL)
                    .schedule();
        } catch (RuntimeException e) {
            if (timeout != null) timeout.cancel();
            if (retry != null) retry.cancel();
            throw e;
        }
        InviteTasks tasks = new InviteTasks(invite, retry, timeout);
        InviteTasks replaced = tasksByToken.put(invite.token(), tasks);
        if (replaced != null) replaced.cancel();
        publishRingtoneStarts(jedis, invite);

        target.sendMessage(Component.text("☎ ", NamedTextColor.GOLD)
                .append(Component.text(invite.callerName(), NamedTextColor.AQUA))
                .append(Component.text(" is calling you! ", NamedTextColor.GOLD))
                .append(accept)
                .append(Component.space())
                .append(decline));
        caller.sendMessage(Component.text("Calling ", NamedTextColor.YELLOW)
                .append(Component.text(invite.targetName(), NamedTextColor.AQUA))
                .append(Component.text("…", NamedTextColor.YELLOW)));
    }

    private void retryRingtoneStart(String token) {
        synchronized (acceptanceLifecycleLock) {
            InviteTasks tasks = tasksByToken.get(token);
            if (!running || tasks == null || !invites.isPending(
                    token, tasks.invite().targetId(), System.currentTimeMillis())) {
                return;
            }
            publishRingtoneStarts(tasks.invite());
        }
    }

    private void expireInvite(String token) {
        Optional<CallInviteRegistry.Invite> expired;
        synchronized (acceptanceLifecycleLock) {
            expired = invites.expire(token, Long.MAX_VALUE);
            if (expired.isPresent()) {
                registerRingtoneStopRetriesLocked(expired.get());
            } else {
                cancelInviteTasks(token);
            }
        }
        if (expired.isEmpty()) return;
        CallInviteRegistry.Invite invite = expired.get();
        publishRingtoneStops(invite);
        plugin.getServer().getPlayer(invite.callerId()).ifPresent(player ->
                player.sendMessage(Component.text(invite.targetName(), NamedTextColor.AQUA)
                        .append(Component.text(" didn't answer.", NamedTextColor.YELLOW))));
        plugin.getServer().getPlayer(invite.targetId()).ifPresent(player ->
                player.sendMessage(Component.text("Missed call from ", NamedTextColor.YELLOW)
                        .append(Component.text(invite.callerName(), NamedTextColor.AQUA))
                        .append(Component.text(".", NamedTextColor.YELLOW))));
    }

    private CallInviteRegistry.CallCredentials activeCall(Jedis jedis, UUID playerId, long nowMillis) {
        String groupValue = jedis.get(playerGroupKey(playerId));
        if (groupValue == null) return null;
        try {
            UUID groupId = UUID.fromString(groupValue);
            String password = jedis.get(callKey(groupId));
            if (!isValidPassword(password)) return null;
            return new CallInviteRegistry.CallCredentials(groupId, password,
                    nowMillis + TimeUnit.SECONDS.toMillis(CALL_TTL_SECONDS));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void submit(Player feedback, Runnable operation) {
        if (!running) {
            error(feedback, "Voice calls are not available right now.");
            return;
        }
        try {
            worker.execute(operation);
        } catch (RejectedExecutionException e) {
            error(feedback, "The call service is busy; please try again.");
        }
    }

    private void cancelInviteTasks(String token) {
        InviteTasks tasks = tasksByToken.remove(token);
        if (tasks != null) tasks.cancel();
    }

    private CallInviteRegistry.Invite cancelInviteTasksForTarget(String token, UUID targetId) {
        InviteTasks tasks = tasksByToken.get(token);
        if (tasks == null || !tasks.invite().targetId().equals(targetId)
                || !tasksByToken.remove(token, tasks)) {
            return null;
        }
        tasks.cancel();
        return tasks.invite();
    }

    /** Must be called while holding {@link #acceptanceLifecycleLock}. */
    private void registerRingtoneStopRetriesLocked(CallInviteRegistry.Invite invite) {
        cancelInviteTasks(invite.token());
        if (stopRetriesByToken.containsKey(invite.token())) return;
        if (!running) return;
        long retryDelayMillis = ringtoneStopRetryDelayMillis(
                invite.expiresAtMillis(), System.currentTimeMillis());
        if (retryDelayMillis == 0L) return;

        StopRetry retry = new StopRetry(invite);
        stopRetriesByToken.put(invite.token(), retry);
        try {
            ScheduledTask task = plugin.getServer().getScheduler()
                    .buildTask(plugin, () -> retryRingtoneStop(invite.token()))
                    .delay(Duration.ofMillis(retryDelayMillis))
                    .repeat(RING_STOP_RETRY_INTERVAL)
                    .schedule();
            retry.attach(task);
        } catch (RuntimeException e) {
            // Keep the unscheduled entry visible so shutdown/reload can still
            // include this terminal invitation in its final STOP publication.
            plugin.getLogger().warn("Could not schedule voice call ringtone stops", e);
        }
    }

    private void retryRingtoneStop(String token) {
        StopRetry retry;
        synchronized (acceptanceLifecycleLock) {
            retry = stopRetriesByToken.get(token);
            if (retry == null) return;
            if (!running || !shouldRetryRingtoneStop(
                    retry.invite().expiresAtMillis(), System.currentTimeMillis())) {
                if (stopRetriesByToken.remove(token, retry)) retry.cancel();
                return;
            }
        }
        publishRingtoneStopRetry(retry.invite());
    }

    private void publishRingtoneStops(CallInviteRegistry.Invite invite) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        try (Jedis jedis = pool.getResource()) {
            publishRingtoneStops(jedis, invite);
        } catch (Exception e) {
            if (running) {
                plugin.getLogger().warn("Could not stop voice call ringtones", e);
            }
        }
    }

    private void publishRingtoneStopRetry(CallInviteRegistry.Invite invite) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        try (Jedis jedis = pool.getResource()) {
            publishRingtoneStopRetry(jedis, invite);
        } catch (Exception e) {
            if (running) {
                plugin.getLogger().warn("Could not retry voice call ringtone stops", e);
            }
        }
    }

    private void publishRingtoneStarts(CallInviteRegistry.Invite invite) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        try (Jedis jedis = pool.getResource()) {
            publishRingtoneStarts(jedis, invite);
        } catch (Exception e) {
            if (running) {
                plugin.getLogger().warn("Could not retry voice call ringtones", e);
            }
        }
    }

    private static void publishRingtoneStarts(Jedis jedis,
                                               CallInviteRegistry.Invite invite) {
        jedis.publish(CONTROL_CHANNEL, encodeCallRingStart(
                invite.token(), invite.callerId(), RingDirection.OUTGOING,
                invite.expiresAtMillis()));
        jedis.publish(CONTROL_CHANNEL, encodeCallRingStart(
                invite.token(), invite.targetId(), RingDirection.INCOMING,
                invite.expiresAtMillis()));
    }

    private static void publishRingtoneStops(Jedis jedis,
                                              CallInviteRegistry.Invite invite) {
        jedis.publish(CONTROL_CHANNEL, encodeCallRingStop(
                invite.token(), invite.callerId(), RingDirection.OUTGOING));
        jedis.publish(CONTROL_CHANNEL, encodeCallRingStop(
                invite.token(), invite.targetId(), RingDirection.INCOMING));
    }

    private static void publishRingtoneStopRetry(Jedis jedis,
                                                  CallInviteRegistry.Invite invite) {
        jedis.eval(RETRY_CALL_RING_STOP_SCRIPT, java.util.List.of(),
                encodeCallRingStopRetryArguments(invite));
    }

    static java.util.List<String> encodeCallRingStopRetryArguments(
            CallInviteRegistry.Invite invite) {
        return java.util.List.of(CONTROL_CHANNEL,
                encodeCallRingStop(invite.token(), invite.callerId(), RingDirection.OUTGOING),
                encodeCallRingStop(invite.token(), invite.targetId(), RingDirection.INCOMING),
                Long.toString(invite.expiresAtMillis()));
    }

    private String randomSecret(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private boolean reserveInviteSlot(UUID callerId, long nowMillis) {
        java.util.concurrent.atomic.AtomicBoolean reserved =
                new java.util.concurrent.atomic.AtomicBoolean();
        lastInviteAt.compute(callerId, (id, previous) -> {
            if (previous == null || nowMillis - previous >= INVITE_COOLDOWN_MILLIS) {
                reserved.set(true);
                return nowMillis;
            }
            return previous;
        });
        return reserved.get();
    }

    private static boolean isValidPassword(String password) {
        if (password == null || password.length() < 22 || password.length() > 64) return false;
        for (int i = 0; i < password.length(); i++) {
            char character = password.charAt(i);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') {
                return false;
            }
        }
        return true;
    }

    static String encodeCallJoin(CallInviteRegistry.CallCredentials call, UUID playerId) {
        return String.join(SEPARATOR, OP_CALL_JOIN, call.groupId().toString(),
                playerId.toString(), call.password());
    }

    static String encodeCallRingStart(String token, UUID playerId,
                                      RingDirection direction, long expiresAtMillis) {
        requireValidRingToken(token);
        if (playerId == null || direction == null || expiresAtMillis <= 0L) {
            throw new IllegalArgumentException("Invalid call ringtone start");
        }
        return String.join(SEPARATOR, OP_CALL_RING_START, token, playerId.toString(),
                direction.name(), Long.toString(expiresAtMillis));
    }

    static String encodeCallRingStop(String token, UUID playerId, RingDirection direction) {
        requireValidRingToken(token);
        if (playerId == null || direction == null) {
            throw new IllegalArgumentException("Invalid call ringtone stop");
        }
        return String.join(SEPARATOR, OP_CALL_RING_STOP, token, playerId.toString(),
                direction.name());
    }

    static boolean shouldRetryRingtoneStop(long expiresAtMillis, long nowMillis) {
        return nowMillis < expiresAtMillis;
    }

    static long ringtoneStopRetryDelayMillis(long expiresAtMillis, long nowMillis) {
        if (!shouldRetryRingtoneStop(expiresAtMillis, nowMillis)) return 0L;
        long remainingMillis = expiresAtMillis - nowMillis;
        return Math.min(RING_STOP_RETRY_INTERVAL.toMillis(),
                Math.max(1L, remainingMillis / 2L));
    }

    private static void requireValidRingToken(String token) {
        if (token == null || token.length() < 22 || token.length() > 64) {
            throw new IllegalArgumentException("Invalid call ringtone token");
        }
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') {
                throw new IllegalArgumentException("Invalid call ringtone token");
            }
        }
    }

    static String acceptCommand(String token) {
        return "/call accept " + token;
    }

    static String declineCommand(String token) {
        return "/call decline " + token;
    }

    private static String callKey(UUID groupId) {
        return CALL_KEY_PREFIX + groupId;
    }

    private static String playerGroupKey(UUID playerId) {
        return PLAYER_GROUP_KEY_PREFIX + playerId;
    }

    private static String callTargetKey(UUID playerId) {
        return CALL_TARGET_KEY_PREFIX + playerId;
    }

    private static void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    enum RingDirection { INCOMING, OUTGOING }

    private record InviteTasks(CallInviteRegistry.Invite invite, ScheduledTask retry,
                               ScheduledTask timeout) {
        void cancel() {
            retry.cancel();
            timeout.cancel();
        }
    }

    private static final class StopRetry {
        private final CallInviteRegistry.Invite invite;
        private ScheduledTask task;
        private boolean cancelled;

        private StopRetry(CallInviteRegistry.Invite invite) {
            this.invite = invite;
        }

        CallInviteRegistry.Invite invite() {
            return invite;
        }

        synchronized void attach(ScheduledTask scheduledTask) {
            if (cancelled) {
                scheduledTask.cancel();
            } else {
                task = scheduledTask;
            }
        }

        synchronized void cancel() {
            cancelled = true;
            if (task != null) task.cancel();
        }
    }
}
