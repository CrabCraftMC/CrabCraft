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
import java.util.HashSet;
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

    private final java.util.Map<UUID, AudioSubscription> audioSubs = new ConcurrentHashMap<>();

    RedisVoiceBus(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");
    }

    void start(BiConsumer<UUID, byte[]> audioHandler, Consumer<String> rosterHandler) {
        this.audioHandler = audioHandler;
        this.rosterHandler = rosterHandler;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        // Bound resource waits: getResource() otherwise blocks forever when
        // the pool is exhausted, which would stall the audio subscriber
        // threads (fetchPlayerHome runs on them) during a Redis hiccup.
        poolConfig.setMaxWait(java.time.Duration.ofMillis(1500));
        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
        }

        startRosterSubscriber();
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
            while (!Thread.currentThread().isInterrupted()) {
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
                    if (Thread.currentThread().isInterrupted()) break;
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

    /** Subscribe to a per-group audio channel. Idempotent. */
    void subscribeAudio(UUID groupId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        audioSubs.computeIfAbsent(groupId, this::createAudioSubscription);
    }

    private AudioSubscription createAudioSubscription(UUID groupId) {
        AudioSubscription sub = new AudioSubscription(groupId);
        sub.start();
        return sub;
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

    void deletePlayerGroup(UUID playerId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        runAsync(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.del(VoiceMessages.playerGroupKey(playerId));
            } catch (Exception ignored) {}
        });
    }

    void shutdown() {
        if (rosterPubSub != null) {
            try { rosterPubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (rosterSubscriberThread != null) {
            rosterSubscriberThread.interrupt();
        }
        Set<UUID> groupIds = new HashSet<>(audioSubs.keySet());
        for (UUID id : groupIds) {
            AudioSubscription sub = audioSubs.remove(id);
            if (sub != null) sub.shutdown();
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
                boolean warned = false;
                while (!Thread.currentThread().isInterrupted()) {
                    JedisPool pool = jedisPool;
                    if (pool == null || pool.isClosed()) break;
                    try (Jedis jedis = pool.getResource()) {
                        if (warned) {
                            plugin.getLogger().info("Voice audio Redis subscriber reconnected for " + groupId + ".");
                            warned = false;
                        }
                        jedis.subscribe(pubSub, channelBytes);
                    } catch (NoClassDefFoundError e) {
                        break;
                    } catch (Exception e) {
                        if (Thread.currentThread().isInterrupted()) break;
                        if (!warned) {
                            plugin.getLogger().warning("Voice audio Redis subscriber unavailable for " + groupId
                                    + "; reconnecting in 3s: " + e.getMessage());
                            warned = true;
                        } else {
                            plugin.getLogger().fine("Voice audio subscriber disconnected for " + groupId + ": "
                                    + e.getMessage());
                        }
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
