package crabcraft.net.crabUtilities;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mirror of the Velocity-owned login streak data, fed by:
 * <ul>
 *   <li>An async Redis HGET on player join (warm the cache for whoever
 *       just connected).</li>
 *   <li>A persistent {@code crabutilities:streaks-updates} subscription
 *       that pushes every recorded login into the cache live.</li>
 * </ul>
 *
 * <p>PlaceholderAPI requests are served from this in-memory map without
 * ever touching Redis on the request path, so placeholder rendering
 * stays in microseconds even when Redis is slow or unreachable.
 */
public class LoginStreakCache implements Listener {

    public static final String HASH_KEY = "crabutilities:streaks";
    public static final String UPDATE_CHANNEL = "crabutilities:streaks-updates";

    private final CrabUtilities plugin;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;

    private final ConcurrentHashMap<UUID, StreakSnapshot> cache = new ConcurrentHashMap<>();

    private JedisPool jedisPool;
    private SubscriberThread subscriberThread;

    public LoginStreakCache(CrabUtilities plugin) {
        this.plugin = plugin;
        this.redisHost = plugin.getConfig().getString("redis.host", "localhost");
        this.redisPort = plugin.getConfig().getInt("redis.port", 6379);
        this.redisPassword = plugin.getConfig().getString("redis.password", "");
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword);
        } else {
            this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000);
        }

        primeOnlinePlayers();

        subscriberThread = new SubscriberThread();
        subscriberThread.setName("crabutilities-streak-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        plugin.getLogger().info("Login streak cache started; Redis will be retried asynchronously if unavailable.");
    }

    public void shutdown() {
        if (subscriberThread != null) {
            subscriberThread.cancelled = true;
            try {
                if (subscriberThread.subscriber != null && subscriberThread.subscriber.isSubscribed()) {
                    subscriberThread.subscriber.unsubscribe();
                }
            } catch (Exception ignored) {}
            subscriberThread.interrupt();
            subscriberThread = null;
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }

    public StreakSnapshot get(UUID uuid) {
        return cache.get(uuid);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> refreshOne(uuid));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Drop offline players to keep the cache tight; the next login
        // re-warms it before placeholders fire.
        cache.remove(event.getPlayer().getUniqueId());
    }

    private void primeOnlinePlayers() {
        Runnable capture = () -> {
            List<UUID> onlinePlayers = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getUniqueId)
                    .toList();
            if (!onlinePlayers.isEmpty()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> primeFromHash(onlinePlayers));
            }
        };
        if (Bukkit.isPrimaryThread()) {
            capture.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, capture);
        }
    }

    private void primeFromHash(List<UUID> onlinePlayers) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            for (UUID uuid : onlinePlayers) {
                String json = jedis.hget(HASH_KEY, uuid.toString());
                if (json != null) ingest(json);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to prime login streak cache: " + e.getMessage());
        }
    }

    private void refreshOne(UUID uuid) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.hget(HASH_KEY, uuid.toString());
            if (json != null) ingest(json);
        } catch (Exception e) {
            plugin.getLogger().fine("Failed to load streak for " + uuid + ": " + e.getMessage());
        }
    }

    private void ingest(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
            int current = obj.has("current_streak") ? obj.get("current_streak").getAsInt() : 0;
            int pending = obj.has("pending_streak") ? obj.get("pending_streak").getAsInt() : current;
            int longest = obj.has("longest_streak") ? obj.get("longest_streak").getAsInt() : 0;
            long lastLogin = obj.has("last_login_at") ? obj.get("last_login_at").getAsLong() : 0L;
            long startedAt = obj.has("streak_started_at") ? obj.get("streak_started_at").getAsLong() : lastLogin;
            long expiresAt = obj.has("expires_at") ? obj.get("expires_at").getAsLong() : 0L;
            boolean active = obj.has("active") ? obj.get("active").getAsBoolean() : current > 0;
            cache.put(uuid, new StreakSnapshot(current, pending, longest, lastLogin, startedAt, expiresAt, active));
        } catch (Exception e) {
            plugin.getLogger().fine("Bad streak update payload: " + e.getMessage());
        }
    }

    public Map<UUID, StreakSnapshot> snapshot() {
        return Map.copyOf(cache);
    }

    public static final class StreakSnapshot {
        public final int currentStreak;
        public final int pendingStreak;
        public final int longestStreak;
        public final long lastLoginAt;
        public final long streakStartedAt;
        public final long expiresAt;
        public final boolean active;

        public StreakSnapshot(int currentStreak, int pendingStreak, int longestStreak,
                              long lastLoginAt, long streakStartedAt,
                              long expiresAt, boolean active) {
            this.currentStreak = currentStreak;
            this.pendingStreak = pendingStreak;
            this.longestStreak = longestStreak;
            this.lastLoginAt = lastLoginAt;
            this.streakStartedAt = streakStartedAt;
            this.expiresAt = expiresAt;
            this.active = active;
        }
    }

    private final class SubscriberThread extends Thread {
        volatile boolean cancelled = false;
        volatile JedisPubSub subscriber;

        @Override
        public void run() {
            // Reconnect loop: a single JedisPubSub.subscribe blocks
            // until the connection drops, so we wrap it in a loop with
            // backoff for transient Redis outages.
            long backoffMs = 1000L;
            boolean warned = false;
            while (!cancelled) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) return;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Login streak Redis subscription reconnected.");
                        warned = false;
                        primeOnlinePlayers();
                    }
                    subscriber = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            if (UPDATE_CHANNEL.equals(channel)) ingest(message);
                        }
                    };
                    backoffMs = 1000L;
                    jedis.subscribe(subscriber, UPDATE_CHANNEL);
                } catch (Exception e) {
                    if (cancelled) return;
                    if (!warned) {
                        plugin.getLogger().warning("Login streak Redis subscription unavailable; retrying: "
                                + e.getMessage());
                        warned = true;
                    } else {
                        plugin.getLogger().fine("Streak subscription dropped: " + e.getMessage());
                    }
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { return; }
                    backoffMs = Math.min(30_000L, backoffMs * 2L);
                }
            }
        }
    }
}
