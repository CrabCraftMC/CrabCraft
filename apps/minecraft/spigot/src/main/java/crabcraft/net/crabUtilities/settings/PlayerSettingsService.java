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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Network-wide store of per-player {@code /settings} preferences.
 *
 * <p>Backed by a single Redis hash ({@link #HASH_KEY}) keyed by player UUID
 * with a small JSON value, so a player's choices follow them across every
 * backend on the proxy. The on-disk source of truth is Redis; this class
 * keeps an in-memory {@link ConcurrentHashMap} mirror for the players
 * currently online here so reads on the hot path (the phantom reset task,
 * the GUI) never touch Redis.
 *
 * <p>Lifecycle mirrors {@link crabcraft.net.crabUtilities.LoginStreakCache}:
 * a {@link JedisPool} with a 2s timeout, an async HGET on join to warm the
 * cache, and eviction on quit. Writes (toggles) update the cache
 * synchronously and are flushed to Redis on an async task. If Redis is
 * unavailable the cache still serves the session from memory and absent
 * records fall back to {@link PlayerSettings#DEFAULTS} (phantoms OFF), which
 * is the safe default.
 */
public class PlayerSettingsService implements Listener {

    public static final String HASH_KEY = "crabutilities:settings";

    private final CrabUtilities plugin;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;

    private final ConcurrentHashMap<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();

    /**
     * Invoked on the main thread whenever a player's settings become known
     * (loaded on join) or change (toggled). Lets the phantom manager apply
     * the effect promptly instead of waiting for its next sweep.
     */
    private volatile Consumer<UUID> updateListener;

    private volatile JedisPool jedisPool;
    private volatile boolean redisFailureLogged;

    public PlayerSettingsService(CrabUtilities plugin) {
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

        // Warm the cache for anyone already online (e.g. after a /reload).
        primeOnlinePlayers();

        plugin.getLogger().info("Player settings service started; Redis will be retried asynchronously if unavailable.");
    }

    public void shutdown() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
        cache.clear();
    }

    public void setUpdateListener(Consumer<UUID> updateListener) {
        this.updateListener = updateListener;
    }

    /** Returns the cached settings for a player, or {@link PlayerSettings#DEFAULTS} if not loaded. */
    public PlayerSettings get(UUID uuid) {
        return cache.getOrDefault(uuid, PlayerSettings.DEFAULTS);
    }

    public boolean isPhantomsEnabled(UUID uuid) {
        return get(uuid).isPhantomsEnabled();
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
     * Updates a player's phantom preference: mutates the in-memory cache
     * immediately, notifies the update listener so the effect applies now,
     * and flushes the new value to Redis on an async task. Safe to call from
     * the main thread (the command/GUI path).
     */
    public void setPhantomsEnabled(UUID uuid, boolean enabled) {
        // Atomic read-modify-write so a concurrent join-load (refreshOne) can't
        // interleave with the get/put, and so future multi-field settings keep
        // their other values.
        PlayerSettings updated = cache.compute(uuid, (key, current) ->
                (current == null ? PlayerSettings.DEFAULTS : current).withPhantomsEnabled(enabled));
        notifyUpdate(uuid);
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
            // and the update listener still fires (phantoms OFF by default).
            cache.putIfAbsent(uuid, PlayerSettings.DEFAULTS);
            notifyUpdate(uuid);
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
        notifyUpdate(uuid);
    }

    /** Writes a player's settings back to Redis on an async task. */
    private void persist(UUID uuid, PlayerSettings settings) {
        if (jedisPool == null) {
            return;
        }
        String payload = settings.toJson().toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Re-read the pool on the worker thread: shutdown() (e.g. a reload)
            // may have closed it between scheduling and running this task.
            JedisPool pool = jedisPool;
            if (pool == null || pool.isClosed()) {
                return;
            }
            try (Jedis jedis = pool.getResource()) {
                jedis.hset(HASH_KEY, uuid.toString(), payload);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to save settings for " + uuid + ": " + e.getMessage());
            }
        });
    }

    private void notifyUpdate(UUID uuid) {
        Consumer<UUID> listener = updateListener;
        if (listener == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            listener.accept(uuid);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> listener.accept(uuid));
        }
    }
}
