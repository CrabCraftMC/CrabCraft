package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Thin Jedis pub/sub wrapper for the voice bridge.
 *
 * <p>Two flavours of channel:
 * <ul>
 *   <li>Per-group binary audio on {@code crabcraft:svc:audio:&lt;uuid&gt;}.</li>
 *   <li>Single text lifecycle channel {@code crabcraft:svc:roster} for
 *       roster join/leave/heartbeat messages.</li>
 * </ul>
 *
 * <p>Subscriber threads reconnect on failure (3s backoff) like
 * {@code RedisStaffChat}; audio publishes use a single-threaded executor
 * with drop-oldest semantics so a slow Redis can't back up the SVC
 * server thread.
 */
class RedisVoiceBus {

    private final CrabUtilities plugin;
    private final String host;
    private final int port;
    private final String password;

    private JedisPool jedisPool;
    private BiConsumer<UUID, byte[]> audioHandler;
    private Consumer<String> rosterHandler;
    private JedisPubSub rosterPubSub;
    private Thread rosterSubscriberThread;
    private BinaryJedisPubSub audioPubSub;
    private Thread audioSubscriberThread;
    private volatile boolean closing;

    /** Logical subscriptions applied on top of the one shared Redis pattern subscriber. */
    private final Set<UUID> audioSubscriptions = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ThreadPoolExecutor[] audioDispatchers =
            createAudioDispatchers();

    RedisVoiceBus(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");
    }

    void start(BiConsumer<UUID, byte[]> audioHandler, Consumer<String> rosterHandler) {
        closing = false;
        this.audioHandler = audioHandler;
        this.rosterHandler = rosterHandler;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        // Bound resource waits: getResource() otherwise blocks forever when
        // the pool is exhausted, which would stall voice worker threads
        // (fetchPlayerHome runs on them) during a Redis hiccup.
        poolConfig.setMaxWait(java.time.Duration.ofMillis(1500));
        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
        }

        startRosterSubscriber();
        startAudioSubscriber();
        plugin.getLogger().info("Voice bus started; Redis will be retried asynchronously if unavailable.");
    }

    private void startRosterSubscriber() {
        rosterPubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    rosterHandler.accept(message);
                } catch (Throwable t) {
                    plugin.getLogger().fine("Voice roster handler threw: " + t.getMessage());
                }
            }
        };
        rosterSubscriberThread = new Thread(() -> {
            boolean warned = false;
            while (!closing && !Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Voice roster Redis subscriber reconnected.");
                        warned = false;
                    }
                    jedis.subscribe(rosterPubSub, VoiceMessages.ROSTER_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (closing || Thread.currentThread().isInterrupted()) break;
                    if (!warned) {
                        plugin.getLogger().warning(
                                "Voice roster Redis subscriber unavailable; reconnecting in 3s: " + e.getMessage());
                        warned = true;
                    } else {
                        plugin.getLogger().fine("Voice roster subscriber disconnected: " + e.getMessage());
                    }
                    try { Thread.sleep(3000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-VoiceBus-Roster");
        rosterSubscriberThread.setDaemon(true);
        rosterSubscriberThread.start();
    }

    /**
     * Allow inbound audio for a group. Redis itself uses one shared pattern
     * subscription so dynamic calls do not consume a thread and pooled
     * connection apiece.
     */
    void subscribeAudio(UUID groupId) {
        audioSubscriptions.add(groupId);
    }

    /** Stop accepting inbound audio for a group. Idempotent. */
    void unsubscribeAudio(UUID groupId) {
        audioSubscriptions.remove(groupId);
    }

    private void startAudioSubscriber() {
        byte[] channelPattern = (VoiceMessages.AUDIO_CHANNEL_PREFIX + "*")
                .getBytes(StandardCharsets.UTF_8);
        audioPubSub = new BinaryJedisPubSub() {
            @Override
            public void onPMessage(byte[] pattern, byte[] channel, byte[] message) {
                String channelName = new String(channel, StandardCharsets.UTF_8);
                if (!channelName.startsWith(VoiceMessages.AUDIO_CHANNEL_PREFIX)) return;
                UUID groupId;
                try {
                    groupId = UUID.fromString(channelName.substring(
                            VoiceMessages.AUDIO_CHANNEL_PREFIX.length()));
                } catch (IllegalArgumentException e) {
                    return;
                }
                if (!audioSubscriptions.contains(groupId)) return;
                try {
                    byte[] frame = message.clone();
                    audioDispatchers[Math.floorMod(groupId.hashCode(), audioDispatchers.length)]
                            .execute(() -> {
                                try {
                                    audioHandler.accept(groupId, frame);
                                } catch (Throwable t) {
                                    plugin.getLogger().fine(
                                            "Voice audio handler threw: " + t.getMessage());
                                }
                            });
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // The bridge is shutting down.
                }
            }
        };
        audioSubscriberThread = new Thread(() -> {
            boolean warned = false;
            while (!closing && !Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Voice audio Redis subscriber reconnected.");
                        warned = false;
                    }
                    jedis.psubscribe(audioPubSub, channelPattern);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (closing || Thread.currentThread().isInterrupted()) break;
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

    void publishAudio(UUID groupId, byte[] frame) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        audioPublishExecutor.execute(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(VoiceMessages.audioChannel(groupId).getBytes(StandardCharsets.UTF_8), frame);
            } catch (Exception e) {
                plugin.getLogger().fine("Voice audio publish failed: " + e.getMessage());
            }
        });
    }

    void publishRoster(String message) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        runAsync(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(VoiceMessages.ROSTER_CHANNEL, message);
            } catch (Exception e) {
                plugin.getLogger().warning("Voice roster publish failed: " + e.getMessage());
            }
        });
    }

    /**
     * Bukkit rejects new tasks once the plugin is disabling (the enabled
     * flag flips before onDisable runs), which made the shutdown
     * leave-broadcast throw and abort the rest of the cleanup. Fall back
     * to running inline in that case — the shutdown path is not
     * latency-sensitive and the publish has a bounded socket timeout.
     */
    private void runAsync(Runnable task) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (IllegalPluginAccessException e) {
            task.run();
        }
    }

    /** Returns the home backend for the speaker, or null if not set. */
    String fetchPlayerHome(UUID playerId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return null;
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(VoiceMessages.playerHomeKey(playerId));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the group UUID the player was in (across any backend),
     * or null if no record / expired. Used for auto-rejoin on server hop.
     */
    String fetchPlayerGroup(UUID playerId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return null;
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(VoiceMessages.playerGroupKey(playerId));
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetch pending call destinations for a local-player snapshot in one Redis round trip. */
    java.util.Map<UUID, String> fetchCallTargets(java.util.List<UUID> playerIds) {
        if (playerIds.isEmpty()) return java.util.Map.of();
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return java.util.Map.of();
        String[] keys = playerIds.stream()
                .map(VoiceMessages::callTargetKey)
                .toArray(String[]::new);
        try (Jedis jedis = pool.getResource()) {
            java.util.List<String> values = jedis.mget(keys);
            java.util.Map<UUID, String> targets = new java.util.HashMap<>();
            for (int index = 0; index < playerIds.size(); index++) {
                String value = values.get(index);
                if (value != null) targets.put(playerIds.get(index), value);
            }
            return targets;
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    /** Returns the secret for a live dynamic call, or null if it has expired. */
    String fetchCallMetadata(UUID groupId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return null;
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(VoiceMessages.callMetadataKey(groupId));
        } catch (Exception e) {
            return null;
        }
    }

    void writeCallMetadata(UUID groupId, String callPassword, long ttlSeconds) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        runAsync(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.setex(VoiceMessages.callMetadataKey(groupId), ttlSeconds, callPassword);
            } catch (Exception e) {
                // The secret is deliberately omitted from all diagnostics.
                plugin.getLogger().fine("writeCallMetadata failed for " + groupId);
            }
        });
    }

    void refreshCallMetadata(UUID groupId, long ttlSeconds) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        runAsync(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.expire(VoiceMessages.callMetadataKey(groupId), ttlSeconds);
            } catch (Exception e) {
                plugin.getLogger().fine("refreshCallMetadata failed for " + groupId);
            }
        });
    }

    void writePlayerGroup(UUID playerId, UUID groupId, long ttlSeconds) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        runAsync(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.setex(VoiceMessages.playerGroupKey(playerId), ttlSeconds, groupId.toString());
            } catch (Exception e) {
                plugin.getLogger().fine("writePlayerGroup failed: " + e.getMessage());
            }
        });
    }

    /** Delete the mapping only if it still points at the group being left. */
    void deletePlayerGroup(UUID playerId, UUID expectedGroupId) {
        compareAndDelete(VoiceMessages.playerGroupKey(playerId), expectedGroupId);
    }

    /** Clear a durable join request only if it still targets this call. */
    void deleteCallTarget(UUID playerId, UUID expectedGroupId) {
        compareAndDelete(VoiceMessages.callTargetKey(playerId), expectedGroupId);
    }

    private void compareAndDelete(String key, UUID expectedGroupId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        runAsync(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.eval(
                        "if redis.call('get', KEYS[1]) == ARGV[1] "
                                + "then return redis.call('del', KEYS[1]) else return 0 end",
                        java.util.List.of(key),
                        java.util.List.of(expectedGroupId.toString()));
            } catch (Exception ignored) {}
        });
    }

    void shutdown() {
        closing = true;
        if (rosterPubSub != null) {
            try { rosterPubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (rosterSubscriberThread != null) {
            rosterSubscriberThread.interrupt();
        }
        if (audioPubSub != null) {
            try { audioPubSub.punsubscribe(); } catch (Exception ignored) {}
        }
        if (audioSubscriberThread != null) audioSubscriberThread.interrupt();
        joinSubscriber(rosterSubscriberThread);
        joinSubscriber(audioSubscriberThread);
        audioSubscriptions.clear();
        for (java.util.concurrent.ThreadPoolExecutor dispatcher : audioDispatchers) {
            dispatcher.shutdownNow();
        }
        audioPublishExecutor.shutdownNow();
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }

    /**
     * Single-threaded executor for audio publishes. Bounded queue with
     * drop-oldest semantics — voice is real-time, latency &gt; loss.
     */
    private final java.util.concurrent.ThreadPoolExecutor audioPublishExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(512),
                    r -> {
                        Thread t = new Thread(r, "CrabUtilities-VoiceBus-AudioPublish");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    private static java.util.concurrent.ThreadPoolExecutor[] createAudioDispatchers() {
        int shardCount = 4;
        java.util.concurrent.ThreadPoolExecutor[] dispatchers =
                new java.util.concurrent.ThreadPoolExecutor[shardCount];
        for (int shard = 0; shard < shardCount; shard++) {
            int shardNumber = shard + 1;
            dispatchers[shard] = new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(256),
                    runnable -> {
                        Thread thread = new Thread(runnable,
                                "CrabUtilities-VoiceBus-AudioInbound-" + shardNumber);
                        thread.setDaemon(true);
                        return thread;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
        }
        return dispatchers;
    }

    private static void joinSubscriber(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try {
            thread.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
