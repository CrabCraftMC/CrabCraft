package crabcraft.net.crabUtilities.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import crabcraft.net.crabUtilities.CrabUtilities;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backend (Spigot) mirror of the per-player {@code /settings} preferences.
 *
 * <p>The Velocity proxy owns the canonical copy (Postgres), and exposes it
 * over Redis: a hash ({@link #HASH_KEY}) keyed by UUID that this class HGETs to
 * warm its in-memory {@link ConcurrentHashMap} on join, so hot-path reads (the
 * phantom guards, the dialog, mention pings) never touch Redis. This class does
 * not write the hash directly; a toggle updates the local cache immediately
 * (optimistic) and publishes a change request on {@link #SET_CHANNEL}, which the
 * proxy persists to Postgres and writes back to the hash.
 *
 * <p>Lifecycle mirrors {@link crabcraft.net.crabUtilities.LoginStreakCache}: a
 * {@link JedisPool} with a 2s timeout, an async HGET on join, and eviction on
 * quit. If Redis is unavailable the cache still serves the session from memory
 * and absent records fall back to {@link PlayerSettings#DEFAULTS} (phantoms OFF,
 * mention pings on, messages accepted), which are the safe defaults.
 */
public class PlayerSettingsService implements Listener {

    public static final String HASH_KEY = "crabutilities:settings";
    public static final String SET_CHANNEL = "crabutilities:settings-set";
    public static final String UPDATE_CHANNEL = "crabutilities:settings-updates";

    private final CrabUtilities plugin;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;

    private final ConcurrentHashMap<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();

    private volatile JedisPool jedisPool;
    private SubscriberThread subscriberThread;
    private volatile boolean redisFailureLogged;

    public PlayerSettingsService(CrabUtilities plugin) {
        this.plugin = plugin;
        this.redisHost = plugin.getConfig().getString("redis.host", "localhost");
        this.redisPort = plugin.getConfig().getInt("redis.port", 6379);
        this.redisPassword = plugin.getConfig().getString("redis.password", "");
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        // One connection is held by the persistent subscriber; leave headroom
        // for concurrent join HGETs and change publishes.
        poolConfig.setMaxTotal(4);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword);
        } else {
            this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000);
        }

        // Warm the cache for anyone already online (e.g. after a /reload).
        primeOnlinePlayers();

        // Live updates from the proxy (the canonical owner) keep the cache in
        // sync even when a join HGET races ahead of the proxy's load/seed.
        subscriberThread = new SubscriberThread();
        subscriberThread.setName("crabutilities-settings-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        plugin.getLogger().info("Player settings service started; Redis will be retried asynchronously if unavailable.");
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
        cache.clear();
    }

    /** Applies a settings broadcast ({@code {uuid, ...fields}}) to the cache. */
    private void ingest(String message) {
        try {
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
            UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
            cache.put(uuid, PlayerSettings.fromJson(obj));
        } catch (Exception e) {
            plugin.getLogger().fine("Bad settings update payload: " + e.getMessage());
        }
    }

    /** Returns the cached settings for a player, or {@link PlayerSettings#DEFAULTS} if not loaded. */
    public PlayerSettings get(UUID uuid) {
        return cache.getOrDefault(uuid, PlayerSettings.DEFAULTS);
    }

    public PhantomMode getPhantomMode(UUID uuid) {
        return get(uuid).getPhantomMode();
    }

    public boolean isMentionPingsEnabled(UUID uuid) {
        return get(uuid).isMentionPings();
    }

    public boolean isAcceptingMessages(UUID uuid) {
        return get(uuid).isAcceptMessages();
    }

    /**
     * Whether this player's record has been resolved yet (loaded from Redis,
     * or seeded with defaults when Redis is unavailable). Callers that would
     * otherwise act on the {@link PlayerSettings#DEFAULTS} fallback — e.g. the
     * settings screen, which must not show/save a stale default over a real
     * stored value — should wait until this returns true.
     */
    public boolean isLoaded(UUID uuid) {
        return cache.containsKey(uuid);
    }

    /**
     * Updates a player's phantom mode: mutates the in-memory cache immediately
     * and flushes the new value to Redis on an async task. Safe to call from
     * the main thread (the command / dialog path).
     */
    public void setPhantomMode(UUID uuid, PhantomMode mode) {
        update(uuid, current -> current.withPhantomMode(mode));
    }

    public void setMentionPings(UUID uuid, boolean value) {
        update(uuid, current -> current.withMentionPings(value));
    }

    public void setAcceptingMessages(UUID uuid, boolean value) {
        update(uuid, current -> current.withAcceptMessages(value));
    }

    /** Replaces every setting at once (used by the dialog, which submits them together). */
    public void setAll(UUID uuid, PhantomMode mode, boolean mentionPings, boolean acceptMessages) {
        update(uuid, current -> new PlayerSettings(mode, mentionPings, acceptMessages));
    }

    /**
     * Atomic read-modify-write of a player's settings, then publish the change.
     * The compute keeps any other fields a concurrent join-load might have set,
     * and never interleaves with the get/put.
     */
    private void update(UUID uuid, java.util.function.UnaryOperator<PlayerSettings> change) {
        PlayerSettings updated = cache.compute(uuid, (key, current) ->
                change.apply(current == null ? PlayerSettings.DEFAULTS : current));
        persist(uuid, updated);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> refreshOne(uuid));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Drop offline players to keep the cache tight; the next login
        // re-warms it from Redis before anything reads it.
        cache.remove(event.getPlayer().getUniqueId());
    }

    private void primeOnlinePlayers() {
        Runnable capture = () -> {
            List<UUID> online = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getUniqueId)
                    .toList();
            if (!online.isEmpty()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    for (UUID uuid : online) {
                        refreshOne(uuid);
                    }
                });
            }
        };
        if (Bukkit.isPrimaryThread()) {
            capture.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, capture);
        }
    }

    /** Loads one player's record from Redis into the cache (async-safe). */
    private void refreshOne(UUID uuid) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) {
            // Redis not available: seed defaults so the cache has an entry
            // (isLoaded() becomes true) and reads get phantoms OFF by default.
            cache.putIfAbsent(uuid, PlayerSettings.DEFAULTS);
            return;
        }
        PlayerSettings loaded = PlayerSettings.DEFAULTS;
        try (Jedis jedis = pool.getResource()) {
            if (redisFailureLogged) {
                plugin.getLogger().info("Player settings Redis connection recovered.");
                redisFailureLogged = false;
            }
            String json = jedis.hget(HASH_KEY, uuid.toString());
            if (json != null) {
                loaded = PlayerSettings.fromJson(JsonParser.parseString(json).getAsJsonObject());
            }
        } catch (Exception e) {
            if (!redisFailureLogged) {
                plugin.getLogger().warning("Player settings Redis unavailable; using defaults: " + e.getMessage());
                redisFailureLogged = true;
            } else {
                plugin.getLogger().fine("Failed to load settings for " + uuid + ": " + e.getMessage());
            }
        }
        // Seed only if absent: a setting the player toggled during the (possibly
        // slow) Redis round-trip is authoritative and must not be clobbered by
        // the stale loaded value. Matches the Redis-unavailable branch above.
        cache.putIfAbsent(uuid, loaded);
    }

    /**
     * Publishes a change request to the proxy on an async task. The proxy owns
     * the canonical copy (Postgres) and the Redis hash; we only ask it to store
     * the new value. The payload is the settings object plus the player's UUID.
     */
    private void persist(UUID uuid, PlayerSettings settings) {
        if (jedisPool == null) {
            return;
        }
        JsonObject envelope = settings.toJson();
        envelope.addProperty("uuid", uuid.toString());
        String payload = envelope.toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Re-read the pool on the worker thread: shutdown() (e.g. a reload)
            // may have closed it between scheduling and running this task.
            JedisPool pool = jedisPool;
            if (pool == null || pool.isClosed()) {
                return;
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(SET_CHANNEL, payload);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to publish settings for " + uuid + ": " + e.getMessage());
            }
        });
    }

    /**
     * Persistent subscription to {@link #UPDATE_CHANNEL}, reconnecting with
     * backoff for transient Redis outages. Mirrors
     * {@link crabcraft.net.crabUtilities.LoginStreakCache}.
     */
    private final class SubscriberThread extends Thread {
        volatile boolean cancelled = false;
        volatile JedisPubSub subscriber;

        @Override
        public void run() {
            long backoffMs = 1000L;
            boolean warned = false;
            while (!cancelled) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) return;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Player settings Redis subscription reconnected.");
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
                        plugin.getLogger().warning("Player settings Redis subscription unavailable; retrying: "
                                + e.getMessage());
                        warned = true;
                    } else {
                        plugin.getLogger().fine("Settings subscription dropped: " + e.getMessage());
                    }
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { return; }
                    backoffMs = Math.min(30_000L, backoffMs * 2L);
                }
            }
        }
    }
}
