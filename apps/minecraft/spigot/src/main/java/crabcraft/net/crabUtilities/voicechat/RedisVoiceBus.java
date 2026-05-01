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

    boolean start(BiConsumer<UUID, byte[]> audioHandler, Consumer<String> rosterHandler) {
        this.audioHandler = audioHandler;
        this.rosterHandler = rosterHandler;

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

        startRosterSubscriber();
        return true;
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
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(rosterPubSub, VoiceMessages.ROSTER_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    plugin.getLogger().warning(
                            "Voice roster subscriber disconnected, reconnecting in 3s: " + e.getMessage());
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
        if (jedisPool == null) return;
        audioSubs.computeIfAbsent(groupId, this::createAudioSubscription);
    }

    private AudioSubscription createAudioSubscription(UUID groupId) {
        AudioSubscription sub = new AudioSubscription(groupId);
        sub.start();
        return sub;
    }

    void publishAudio(UUID groupId, byte[] frame) {
        if (jedisPool == null) return;
        audioPublishExecutor.execute(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(VoiceMessages.audioChannel(groupId).getBytes(StandardCharsets.UTF_8), frame);
            } catch (Exception e) {
                plugin.getLogger().fine("Voice audio publish failed: " + e.getMessage());
            }
        });
    }

    void publishRoster(String message) {
        if (jedisPool == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(VoiceMessages.ROSTER_CHANNEL, message);
            } catch (Exception e) {
                plugin.getLogger().warning("Voice roster publish failed: " + e.getMessage());
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
