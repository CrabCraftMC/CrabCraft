package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Thin Jedis pub/sub wrapper for the voice bridge.
 *
 * <p>Two flavours of channel:
 * <ul>
 *   <li>One binary pattern subscription for every group audio channel.</li>
 *   <li>One text subscriber for group invalidations and roster messages.</li>
 * </ul>
 *
 * <p>Subscriber threads reconnect on failure (3s backoff) like
 * {@code RedisStaffChat}; audio publishes use a single-threaded executor
 * with a bounded queue so a slow Redis can't back up the SVC server thread.
 */
class RedisVoiceBus {

    private static final String UPSERT_GROUP_SCRIPT = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            if ARGV[3] == '1' then
                redis.call('SADD', KEYS[2], ARGV[1])
            else
                redis.call('SREM', KEYS[2], ARGV[1])
            end
            redis.call('PUBLISH', KEYS[3], ARGV[4])
            return 1
            """;

    private static final String SET_PLAYER_GROUP_SCRIPT = """
            if redis.call('GET', KEYS[5]) ~= ARGV[6] then
                return -1
            end
            local callTarget = redis.call('GET', KEYS[6])
            if callTarget and string.sub(callTarget, 1, 37) ~= ARGV[1] .. ARGV[9] then
                return -2
            end
            local definition = redis.call('HGET', KEYS[2], ARGV[1])
            local registryChanged = definition ~= ARGV[7]
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[7])
            if ARGV[8] == '1' then
                redis.call('SADD', KEYS[3], ARGV[1])
            else
                redis.call('SREM', KEYS[3], ARGV[1])
            end
            if definition ~= ARGV[7] then
                redis.call('PUBLISH', KEYS[4], ARGV[4] .. ARGV[1])
            end
            local old = redis.call('GET', KEYS[1])
            if old and old ~= ARGV[1] then
                local oldMembers = ARGV[3] .. old
                redis.call('SREM', oldMembers, ARGV[2])
                if redis.call('SCARD', oldMembers) == 0
                        and redis.call('SISMEMBER', KEYS[3], old) == 0 then
                    if redis.call('HDEL', KEYS[2], old) > 0 then
                        registryChanged = true
                    end
                    redis.call('PUBLISH', KEYS[4], ARGV[4] .. old)
                end
            end
            redis.call('SETEX', KEYS[1], ARGV[5], ARGV[1])
            redis.call('SADD', ARGV[3] .. ARGV[1], ARGV[2])
            if registryChanged then
                return 1
            end
            return 0
            """;

    private static final String CLEAR_PLAYER_GROUP_SCRIPT = """
            if redis.call('GET', KEYS[5]) ~= ARGV[4] then
                return -1
            end
            if redis.call('GET', KEYS[6]) then
                return -2
            end
            local registryChanged = 0
            local old = redis.call('GET', KEYS[1])
            if not old and ARGV[5] ~= '' then
                old = ARGV[5]
            end
            redis.call('DEL', KEYS[1])
            if old then
                local oldMembers = ARGV[2] .. old
                redis.call('SREM', oldMembers, ARGV[1])
                if redis.call('SCARD', oldMembers) == 0
                        and redis.call('SISMEMBER', KEYS[3], old) == 0 then
                    if redis.call('HDEL', KEYS[2], old) > 0 then
                        registryChanged = 1
                    end
                    redis.call('PUBLISH', KEYS[4], ARGV[3] .. old)
                end
            end
            return registryChanged
            """;

    private static final String PUBLISH_ROSTER_SCRIPT = """
            if ARGV[3] == '1' or redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PUBLISH', KEYS[2], ARGV[2])
            end
            return 0
            """;

    private static final String CLEAR_CALL_TARGET_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then
                return 0
            end
            redis.call('DEL', KEYS[2])
            return 1
            """;

    private static final String PRUNE_GROUPS_SCRIPT = """
            for _, groupId in ipairs(redis.call('HKEYS', KEYS[1])) do
                local membersKey = ARGV[1] .. groupId
                local hadMembers = redis.call('SCARD', membersKey) > 0
                for _, playerId in ipairs(redis.call('SMEMBERS', membersKey)) do
                    if redis.call('GET', ARGV[2] .. playerId) ~= groupId then
                        redis.call('SREM', membersKey, playerId)
                    end
                end
                if not hadMembers and redis.call('SCARD', membersKey) == 0
                        and redis.call('SISMEMBER', KEYS[2], groupId) == 0 then
                    redis.call('HDEL', KEYS[1], groupId)
                    redis.call('DEL', membersKey)
                    redis.call('PUBLISH', KEYS[3], ARGV[3] .. groupId)
                end
            end
            return 1
            """;

    private final CrabUtilities plugin;
    private final String host;
    private final int port;
    private final String password;

    private JedisPool jedisPool;
    private BiConsumer<UUID, byte[]> audioHandler;
    private Consumer<String> lifecycleHandler;
    private JedisPubSub lifecyclePubSub;
    private Thread lifecycleSubscriberThread;
    private BinaryJedisPubSub audioPubSub;
    private Thread audioSubscriberThread;

    RedisVoiceBus(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");
    }

    void start(BiConsumer<UUID, byte[]> audioHandler, Consumer<String> lifecycleHandler) {
        this.audioHandler = audioHandler;
        this.lifecycleHandler = lifecycleHandler;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        // Bound resource waits: getResource() otherwise blocks forever when
        // the pool is exhausted, which would stall voice worker threads
        // during a Redis hiccup.
        poolConfig.setMaxWait(java.time.Duration.ofMillis(1500));
        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
        }

        startLifecycleSubscriber();
        startAudioSubscriber();
        plugin.getLogger().info("Voice bus started; Redis will be retried asynchronously if unavailable.");
    }

    private void startLifecycleSubscriber() {
        lifecyclePubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    lifecycleHandler.accept(message);
                } catch (Throwable t) {
                    plugin.getLogger().fine("Voice lifecycle handler threw: " + t.getMessage());
                }
            }
        };
        lifecycleSubscriberThread = new Thread(() -> {
            boolean warned = false;
            while (!Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Voice lifecycle Redis subscriber reconnected.");
                        warned = false;
                    }
                    jedis.subscribe(lifecyclePubSub,
                            VoiceMessages.LIFECYCLE_CHANNEL, VoiceMessages.ROSTER_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (!warned) {
                        plugin.getLogger().warning(
                                "Voice lifecycle Redis subscriber unavailable; reconnecting in 3s: " + e.getMessage());
                        warned = true;
                    } else {
                        plugin.getLogger().fine("Voice lifecycle subscriber disconnected: " + e.getMessage());
                    }
                    try { Thread.sleep(3000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-VoiceBus-Lifecycle");
        lifecycleSubscriberThread.setDaemon(true);
        lifecycleSubscriberThread.start();
    }

    private void startAudioSubscriber() {
        audioPubSub = new BinaryJedisPubSub() {
            @Override
            public void onPMessage(byte[] pattern, byte[] channel, byte[] message) {
                String channelName = new String(channel, StandardCharsets.UTF_8);
                if (!channelName.startsWith(VoiceMessages.AUDIO_CHANNEL_PREFIX)) return;
                try {
                    UUID groupId = UUID.fromString(
                            channelName.substring(VoiceMessages.AUDIO_CHANNEL_PREFIX.length()));
                    audioHandler.accept(groupId, message);
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed or unrelated channels.
                } catch (Throwable t) {
                    plugin.getLogger().fine("Voice audio handler threw: " + t.getMessage());
                }
            }
        };
        audioSubscriberThread = new Thread(() -> {
            byte[] pattern = (VoiceMessages.AUDIO_CHANNEL_PREFIX + "*")
                    .getBytes(StandardCharsets.UTF_8);
            boolean warned = false;
            while (!Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Voice audio Redis subscriber reconnected.");
                        warned = false;
                    }
                    jedis.psubscribe(audioPubSub, pattern);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (!warned) {
                        plugin.getLogger().warning(
                                "Voice audio Redis subscriber unavailable; reconnecting in 3s: "
                                        + e.getMessage());
                        warned = true;
                    } else {
                        plugin.getLogger().fine("Voice audio subscriber disconnected: " + e.getMessage());
                    }
                    try { Thread.sleep(3000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-VoiceBus-Audio");
        audioSubscriberThread.setDaemon(true);
        audioSubscriberThread.start();
    }

    void publishAudio(UUID groupId, UUID speakerId, byte[] frame, boolean resetMarker) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        if (resetMarker) {
            // Keep other speakers intact, but ensure this speaker's reset follows
            // its last in-flight frame and cannot be displaced by stale audio.
            audioPublishExecutor.getQueue().removeIf(task ->
                    task instanceof AudioPublish publish
                            && publish.speakerId.equals(speakerId));
        } else if (audioPublishExecutor.getQueue().size() >= 64) {
            return;
        }
        try {
            audioPublishExecutor.execute(
                    new AudioPublish(groupId, speakerId, frame));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Plugin is stopping.
        }
    }

    void publishRoster(String message, UUID playerId, String expectedRoute) {
        if (expectedRoute == null) return;
        VoiceMessages.RosterLeave leave = VoiceMessages.decodeRosterLeave(message);
        // A departed route must still be able to retract its own roster entry.
        // Receivers match the complete hop token so this cannot remove a newer hop.
        boolean leaving = leave != null && playerId.equals(leave.playerId())
                && expectedRoute.equals(leave.route());
        submitControl("roster:" + playerId, () -> {
            JedisPool pool = jedisPool;
            if (pool == null || pool.isClosed()) return;
            try (Jedis jedis = pool.getResource()) {
                jedis.eval(PUBLISH_ROSTER_SCRIPT,
                        List.of(VoiceMessages.playerHomeKey(playerId),
                                VoiceMessages.ROSTER_CHANNEL),
                        List.of(expectedRoute, message, leaving ? "1" : "0"));
            } catch (Exception e) {
                plugin.getLogger().warning("Voice roster publish failed: " + e.getMessage());
            }
        });
    }

    void upsertGroup(VoiceMessages.GroupDefinition group, Consumer<Boolean> completion) {
        boolean accepted = submitControl("group:" + group.id(), () -> {
            boolean succeeded = false;
            JedisPool pool = jedisPool;
            try {
                if (pool != null && !pool.isClosed()) {
                    try (Jedis jedis = pool.getResource()) {
                        jedis.eval(UPSERT_GROUP_SCRIPT,
                                List.of(VoiceMessages.GROUPS_REGISTRY_KEY,
                                        VoiceMessages.PERMANENT_GROUPS_KEY,
                                        VoiceMessages.LIFECYCLE_CHANNEL),
                                List.of(group.id().toString(),
                                        VoiceMessages.encodeGroupDefinition(group),
                                        group.permanent() ? "1" : "0",
                                        VoiceMessages.encodeGroupChanged(group.id())));
                        succeeded = true;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "Voice group registry write failed: " + e.getMessage());
            } finally {
                completion.accept(succeeded);
            }
        });
        if (!accepted) completion.accept(false);
    }

    VoiceMessages.GroupDefinition fetchGroup(UUID groupId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return null;
        try {
            try (Jedis jedis = pool.getResource()) {
                return VoiceMessages.decodeGroupDefinition(groupId,
                        jedis.hget(VoiceMessages.GROUPS_REGISTRY_KEY, groupId.toString()));
            }
        } catch (Exception e) {
            return null;
        }
    }

    Map<UUID, VoiceMessages.GroupDefinition> fetchGroups() {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return null;
        try (Jedis jedis = pool.getResource()) {
            Map<UUID, VoiceMessages.GroupDefinition> result = new HashMap<>();
            for (Map.Entry<String, String> entry
                    : jedis.hgetAll(VoiceMessages.GROUPS_REGISTRY_KEY).entrySet()) {
                try {
                    UUID id = UUID.fromString(entry.getKey());
                    VoiceMessages.GroupDefinition group =
                            VoiceMessages.decodeGroupDefinition(id, entry.getValue());
                    if (group != null) result.put(id, group);
                } catch (IllegalArgumentException ignored) {
                    // Skip corrupt registry entries without losing the rest.
                }
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            return null;
        }
    }

    void pruneGroups() {
        submitControl("prune", () -> {
            JedisPool pool = jedisPool;
            if (pool == null || pool.isClosed()) return;
            try (Jedis jedis = pool.getResource()) {
                jedis.eval(PRUNE_GROUPS_SCRIPT,
                        List.of(VoiceMessages.GROUPS_REGISTRY_KEY,
                                VoiceMessages.PERMANENT_GROUPS_KEY,
                                VoiceMessages.LIFECYCLE_CHANNEL),
                        List.of(VoiceMessages.GROUP_MEMBERS_KEY_PREFIX,
                                VoiceMessages.PLAYER_GROUP_KEY_PREFIX,
                                VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP));
            } catch (Exception e) {
                plugin.getLogger().fine("Voice group lease pruning failed: " + e.getMessage());
            }
        });
    }

    /** Returns the Velocity backend + hop token, or null if not set. */
    String fetchPlayerHome(UUID playerId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return null;
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(VoiceMessages.playerHomeKey(playerId));
        } catch (Exception e) {
            return null;
        }
    }

    Map<UUID, String> fetchPlayerHomes(Set<UUID> playerIds) {
        if (playerIds.isEmpty()) return Map.of();
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return null;
        List<UUID> players = List.copyOf(playerIds);
        String[] keys = new String[players.size()];
        for (int i = 0; i < players.size(); i++) {
            keys[i] = VoiceMessages.playerHomeKey(players.get(i));
        }
        try (Jedis jedis = pool.getResource()) {
            List<String> routes = jedis.mget(keys);
            Map<UUID, String> result = new HashMap<>();
            for (int i = 0; i < players.size(); i++) {
                if (routes.get(i) != null) result.put(players.get(i), routes.get(i));
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    ReadResult<Map<UUID, VoiceMessages.CallTarget>> fetchCallTargets(Set<UUID> playerIds) {
        if (playerIds.isEmpty()) return new ReadResult<>(true, Map.of());
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return new ReadResult<>(false, Map.of());
        List<UUID> players = List.copyOf(playerIds);
        String[] keys = new String[players.size()];
        for (int index = 0; index < players.size(); index++) {
            keys[index] = VoiceMessages.callTargetKey(players.get(index));
        }
        try (Jedis jedis = pool.getResource()) {
            List<String> encodedTargets = jedis.mget(keys);
            Map<UUID, VoiceMessages.CallTarget> result = new HashMap<>();
            for (int index = 0; index < players.size(); index++) {
                VoiceMessages.CallTarget target =
                        VoiceMessages.decodeCallTarget(encodedTargets.get(index));
                if (target != null) result.put(players.get(index), target);
            }
            return new ReadResult<>(true, Map.copyOf(result));
        } catch (Exception e) {
            return new ReadResult<>(false, Map.of());
        }
    }

    void clearCallTarget(UUID playerId, VoiceMessages.CallTarget target,
                         String expectedRoute, Runnable completion) {
        if (target == null || expectedRoute == null) {
            completion.run();
            return;
        }
        boolean accepted = submitControl("call-target:" + playerId, () -> {
            JedisPool pool = jedisPool;
            try {
                if (pool != null && !pool.isClosed()) {
                    try (Jedis jedis = pool.getResource()) {
                        jedis.eval(CLEAR_CALL_TARGET_SCRIPT,
                                List.of(VoiceMessages.playerHomeKey(playerId),
                                        VoiceMessages.callTargetKey(playerId)),
                                List.of(expectedRoute, VoiceMessages.encodeCallTarget(target)));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().fine("Call target clear failed: " + e.getMessage());
            } finally {
                completion.run();
            }
        });
        if (!accepted) completion.run();
    }

    /**
     * Returns the group UUID the player was in (across any backend),
     * or null if no record / expired. Used for auto-rejoin on server hop.
     */
    ReadResult<String> fetchPlayerGroup(UUID playerId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return new ReadResult<>(false, null);
        try (Jedis jedis = pool.getResource()) {
            return new ReadResult<>(true,
                    jedis.get(VoiceMessages.playerGroupKey(playerId)));
        } catch (Exception e) {
            return new ReadResult<>(false, null);
        }
    }

    void writePlayerGroup(UUID playerId, VoiceMessages.GroupDefinition group,
                          long ttlSeconds, String expectedRoute,
                          Consumer<Boolean> completion) {
        if (group == null || expectedRoute == null) return;
        boolean accepted = submitControl("membership:" + playerId, () -> {
            boolean definitionChanged = false;
            JedisPool pool = jedisPool;
            try {
                if (pool != null && !pool.isClosed()) {
                    try (Jedis jedis = pool.getResource()) {
                        Object result = jedis.eval(SET_PLAYER_GROUP_SCRIPT,
                                List.of(VoiceMessages.playerGroupKey(playerId),
                                        VoiceMessages.GROUPS_REGISTRY_KEY,
                                        VoiceMessages.PERMANENT_GROUPS_KEY,
                                        VoiceMessages.LIFECYCLE_CHANNEL,
                                        VoiceMessages.playerHomeKey(playerId),
                                        VoiceMessages.callTargetKey(playerId)),
                                List.of(group.id().toString(), playerId.toString(),
                                        VoiceMessages.GROUP_MEMBERS_KEY_PREFIX,
                                        VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP,
                                        Long.toString(ttlSeconds), expectedRoute,
                                        VoiceMessages.encodeGroupDefinition(group),
                                        group.permanent() ? "1" : "0",
                                        VoiceMessages.SEP));
                        definitionChanged = result instanceof Long value && value == 1L;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().fine("writePlayerGroup failed: " + e.getMessage());
            } finally {
                completion.accept(definitionChanged);
            }
        });
        if (!accepted) completion.accept(false);
    }

    void deletePlayerGroup(UUID playerId, UUID previousGroupId, String expectedRoute,
                           Consumer<Boolean> completion) {
        if (expectedRoute == null) return;
        boolean accepted = submitControl("membership:" + playerId, () -> {
            boolean registryChanged = false;
            JedisPool pool = jedisPool;
            try {
                if (pool != null && !pool.isClosed()) {
                    try (Jedis jedis = pool.getResource()) {
                        Object result = jedis.eval(CLEAR_PLAYER_GROUP_SCRIPT,
                                List.of(VoiceMessages.playerGroupKey(playerId),
                                        VoiceMessages.GROUPS_REGISTRY_KEY,
                                        VoiceMessages.PERMANENT_GROUPS_KEY,
                                        VoiceMessages.LIFECYCLE_CHANNEL,
                                        VoiceMessages.playerHomeKey(playerId),
                                        VoiceMessages.callTargetKey(playerId)),
                                List.of(playerId.toString(),
                                        VoiceMessages.GROUP_MEMBERS_KEY_PREFIX,
                                        VoiceMessages.OP_GROUP_CHANGED + VoiceMessages.SEP,
                                        expectedRoute,
                                        previousGroupId == null ? "" : previousGroupId.toString()));
                        registryChanged = result instanceof Long value && value == 1L;
                    }
                }
            } catch (Exception ignored) {
                // The next ungrouped heartbeat retries the authoritative clear.
            } finally {
                completion.accept(registryChanged);
            }
        });
        if (!accepted) completion.accept(false);
    }

    private boolean submitControl(String key, Runnable task) {
        if (controlExecutor.isShutdown()) return false;
        Runnable previous = pendingControlTasks.put(key, task);
        if (previous != null) return true;
        try {
            controlExecutor.execute(() -> {
                Runnable latest = pendingControlTasks.remove(key);
                if (latest != null) latest.run();
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            pendingControlTasks.remove(key, task);
            // Plugin is stopping; no new state should be queued.
            return false;
        }
    }

    void shutdown() {
        controlExecutor.shutdown();
        try {
            if (!controlExecutor.awaitTermination(2L, java.util.concurrent.TimeUnit.SECONDS)) {
                controlExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            controlExecutor.shutdownNow();
        }
        if (lifecyclePubSub != null) {
            try { lifecyclePubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (lifecycleSubscriberThread != null) {
            lifecycleSubscriberThread.interrupt();
        }
        if (audioPubSub != null) {
            try { audioPubSub.punsubscribe(); } catch (Exception ignored) {}
        }
        if (audioSubscriberThread != null) {
            audioSubscriberThread.interrupt();
        }
        audioPublishExecutor.shutdownNow();
        pendingControlTasks.clear();
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }

    private final class AudioPublish implements Runnable {
        private final UUID groupId;
        private final UUID speakerId;
        private final byte[] frame;

        private AudioPublish(UUID groupId, UUID speakerId, byte[] frame) {
            this.groupId = groupId;
            this.speakerId = speakerId;
            this.frame = frame;
        }

        @Override
        public void run() {
            JedisPool pool = jedisPool;
            if (pool == null || pool.isClosed()) return;
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(VoiceMessages.audioChannel(groupId)
                        .getBytes(StandardCharsets.UTF_8), frame);
            } catch (Exception e) {
                plugin.getLogger().fine("Voice audio publish failed: " + e.getMessage());
            }
        }
    }

    /**
     * Normal audio is capped at 64 queued frames. Reset markers may exceed that
     * cap, but replace queued work for the same speaker and are never discarded.
     */
    private final java.util.concurrent.ThreadPoolExecutor audioPublishExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "CrabUtilities-VoiceBus-AudioPublish");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /**
     * Coalesces pending control work by entity while preserving single-threaded
     * ordering. A Redis outage therefore retains the latest state instead of
     * either growing without bound or silently dropping an authoritative leave.
     */
    private final Map<String, Runnable> pendingControlTasks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService controlExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "CrabUtilities-VoiceBus-Control");
                thread.setDaemon(true);
                return thread;
            });

    record ReadResult<T>(boolean succeeded, T value) {}
}
