package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import redis.clients.jedis.BinaryJedisPubSub;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Thin Jedis pub/sub wrapper for the voice bridge.
 *
 * <p>Two channels:
 * <ul>
 *   <li>{@link VoiceMessages#LIFECYCLE_CHANNEL} for group/membership events
 *       — text payloads, decoded by handler.</li>
 *   <li>{@code crabcraft:svc:audio:&lt;groupId&gt;} for opus frames —
 *       binary, subscribed to dynamically as the local server joins
 *       groups so empty-group traffic isn't received.</li>
 * </ul>
 *
 * <p>Subscriber threads reconnect on failure (3s backoff) like
 * {@code RedisStaffChat}; publishes are async via Bukkit's scheduler.
 */
class RedisVoiceBus {

    private final CrabUtilities plugin;
    private final String host;
    private final int port;
    private final String password;

    private JedisPool jedisPool;
    private Thread lifecycleSubscriberThread;
    private JedisPubSub lifecyclePubSub;

    /** Per-group binary subscriber state. */
    private final java.util.Map<UUID, AudioSubscription> audioSubs = new ConcurrentHashMap<>();

    private Consumer<String> lifecycleHandler;
    private BiConsumer<UUID, byte[]> audioHandler;

    RedisVoiceBus(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");
    }

    boolean start(Consumer<String> lifecycleHandler, BiConsumer<UUID, byte[]> audioHandler) {
        this.lifecycleHandler = lifecycleHandler;
        this.audioHandler = audioHandler;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
        } catch (Exception e) {
            plugin.getLogger().severe("Voice bus failed to connect to Redis: " + e.getMessage());
            jedisPool.close();
            jedisPool = null;
            return false;
        }

        lifecyclePubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    lifecycleHandler.accept(message);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Voice lifecycle handler threw: " + t.getMessage());
                }
            }
        };

        lifecycleSubscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(lifecyclePubSub, VoiceMessages.LIFECYCLE_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    plugin.getLogger().warning(
                            "Voice lifecycle subscriber disconnected, reconnecting in 3s: " + e.getMessage());
                    try { Thread.sleep(3000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-VoiceBus-Lifecycle");
        lifecycleSubscriberThread.setDaemon(true);
        lifecycleSubscriberThread.start();

        return true;
    }

    /** Subscribe to a per-group audio channel. Idempotent. */
    void subscribeAudio(UUID groupId) {
        if (jedisPool == null) return;
        audioSubs.computeIfAbsent(groupId, this::createAudioSubscription);
    }

    /** Unsubscribe from a per-group audio channel. Idempotent. */
    void unsubscribeAudio(UUID groupId) {
        AudioSubscription sub = audioSubs.remove(groupId);
        if (sub != null) sub.shutdown();
    }

    private AudioSubscription createAudioSubscription(UUID groupId) {
        AudioSubscription sub = new AudioSubscription(groupId);
        sub.start();
        return sub;
    }

    /** Publish a lifecycle string asynchronously. */
    void publishLifecycle(String message) {
        if (jedisPool == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(VoiceMessages.LIFECYCLE_CHANNEL, message);
            } catch (Exception e) {
                plugin.getLogger().warning("Voice lifecycle publish failed: " + e.getMessage());
            }
        });
    }

    /**
     * Publish an opus audio frame asynchronously. Called at ~50Hz per
     * speaker so we use a dedicated single-threaded executor instead of
     * spawning a Bukkit task per frame.
     */
    void publishAudio(UUID groupId, byte[] frame) {
        if (jedisPool == null) return;
        audioPublishExecutor.execute(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(VoiceMessages.audioChannel(groupId).getBytes(StandardCharsets.UTF_8), frame);
            } catch (Exception e) {
                // High frequency — log at fine level only
                plugin.getLogger().fine("Voice audio publish failed: " + e.getMessage());
            }
        });
    }

    /** Read the current registry hash so a cold-starting backend gets all global groups. */
    java.util.Map<String, String> fetchGroupsRegistry() {
        if (jedisPool == null) return java.util.Map.of();
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll(VoiceMessages.GROUPS_REGISTRY_KEY);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to fetch groups registry from Redis: " + e.getMessage());
            return java.util.Map.of();
        }
    }

    void writeGroupRegistry(UUID id, String encodedCreate) {
        if (jedisPool == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset(VoiceMessages.GROUPS_REGISTRY_KEY, id.toString(), encodedCreate);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to write group registry entry: " + e.getMessage());
            }
        });
    }

    void deleteGroupRegistry(UUID id) {
        if (jedisPool == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hdel(VoiceMessages.GROUPS_REGISTRY_KEY, id.toString());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to delete group registry entry: " + e.getMessage());
            }
        });
    }

    /** Returns the home backend for the speaker, or null if not set. */
    String fetchPlayerHome(UUID playerId) {
        if (jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(VoiceMessages.playerHomeKey(playerId));
        } catch (Exception e) {
            return null;
        }
    }

    void shutdown() {
        if (lifecyclePubSub != null) {
            try { lifecyclePubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (lifecycleSubscriberThread != null) {
            lifecycleSubscriberThread.interrupt();
        }
        Set<UUID> groupIds = new HashSet<>(audioSubs.keySet());
        for (UUID id : groupIds) {
            unsubscribeAudio(id);
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

    private final class AudioSubscription {
        private final UUID groupId;
        private final BinaryJedisPubSub pubSub;
        private Thread thread;

        AudioSubscription(UUID groupId) {
            this.groupId = groupId;
            this.pubSub = new BinaryJedisPubSub() {
                @Override
                public void onMessage(byte[] channel, byte[] message) {
                    try {
                        audioHandler.accept(groupId, message);
                    } catch (Throwable t) {
                        plugin.getLogger().fine("Voice audio handler threw: " + t.getMessage());
                    }
                }
            };
        }

        void start() {
            byte[] channelBytes = VoiceMessages.audioChannel(groupId)
                    .getBytes(StandardCharsets.UTF_8);
            thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.subscribe(pubSub, channelBytes);
                    } catch (NoClassDefFoundError e) {
                        break;
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted()) break;
                        try { Thread.sleep(3000L); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }, "CrabUtilities-VoiceBus-Audio-" + groupId);
            thread.setDaemon(true);
            thread.start();
        }

        void shutdown() {
            try { pubSub.unsubscribe(); } catch (Exception ignored) {}
            if (thread != null) thread.interrupt();
        }
    }
}
