package crabcraft.net.crabUtilities.chat;

import crabcraft.net.crabUtilities.CrabUtilities;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only mirror of the Velocity-owned mute state.
 *
 * <p>The proxy owns mute data; this cache hydrates from the live Redis
 * hash {@code crabutilities:mutes} on startup (field = player uuid, value
 * = expiry epoch millis; {@code 0} = permanent, field absent = not muted)
 * and stays current by subscribing to {@code crabutilities:mute-updates}.
 *
 * <p>Pool build / daemon subscriber idiom mirrors
 * {@code RedisVoiceBus}: a JedisPool with a 2s timeout, a ping on start,
 * and a {@code while(!interrupted)} subscriber thread that reconnects
 * with a ~3s backoff and swallows {@code NoClassDefFoundError} on
 * shutdown.
 */
public class MuteCache {

    private static final String MUTES_HASH = "crabutilities:mutes";
    private static final String UPDATES_CHANNEL = "crabutilities:mute-updates";

    private final CrabUtilities plugin;
    private final String host;
    private final int port;
    private final String password;

    private final ConcurrentHashMap<UUID, Long> mutes = new ConcurrentHashMap<>();

    private JedisPool jedisPool;
    private JedisPubSub pubSub;
    private Thread subscriberThread;

    public MuteCache(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            Map<String, String> live = jedis.hgetAll(MUTES_HASH);
            if (live != null) {
                for (Map.Entry<String, String> e : live.entrySet()) {
                    try {
                        mutes.put(UUID.fromString(e.getKey()), Long.parseLong(e.getValue().trim()));
                    } catch (IllegalArgumentException ignored) {
                        // Skip malformed field/value rather than abort hydration.
                    }
                }
            }
            plugin.getLogger().info("MuteCache loaded " + mutes.size() + " mute(s) from Redis.");
        } catch (Exception e) {
            plugin.getLogger().severe("MuteCache failed to connect to Redis: " + e.getMessage());
            jedisPool.close();
            jedisPool = null;
            return;
        }

        startSubscriber();
    }

    private void startSubscriber() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    handleUpdate(message);
                } catch (Throwable t) {
                    plugin.getLogger().fine("Mute update handler threw: " + t.getMessage());
                }
            }
        };
        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(pubSub, UPDATES_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    plugin.getLogger().warning(
                            "Mute updates subscriber disconnected, reconnecting in 3s: " + e.getMessage());
                    try { Thread.sleep(3000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-MuteCache-Updates");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    /** Parses {@code <uuid>\0<expiry>}: {@code -1} removes, otherwise stores the expiry. */
    private void handleUpdate(String message) {
        int sep = message.indexOf('\0');
        if (sep < 0) return;
        UUID id;
        long expiry;
        try {
            id = UUID.fromString(message.substring(0, sep));
            expiry = Long.parseLong(message.substring(sep + 1).trim());
        } catch (IllegalArgumentException e) {
            return;
        }
        if (expiry == -1L) {
            mutes.remove(id);
        } else {
            mutes.put(id, expiry);
        }
    }

    /**
     * True if the player is currently muted. {@code 0} expiry is permanent;
     * a finite expiry that has passed is lazily evicted and treated as unmuted.
     */
    public boolean isMuted(UUID id) {
        Long expiry = mutes.get(id);
        if (expiry == null) return false;
        if (expiry == 0L) return true;
        if (System.currentTimeMillis() < expiry) return true;
        mutes.remove(id);
        return false;
    }

    /**
     * Remaining millis on a temporary mute, or {@code 0} for permanent / not
     * muted. Use together with {@link #isMuted(UUID)} to pick the message.
     */
    public long remainingMillis(UUID id) {
        Long expiry = mutes.get(id);
        if (expiry == null || expiry == 0L) return 0L;
        long remaining = expiry - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    public void shutdown() {
        if (pubSub != null) {
            try { pubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }
}
