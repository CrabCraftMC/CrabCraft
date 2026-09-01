package crabcraft.net.crabUtilities.velocity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import crabcraft.net.crabUtilities.velocity.db.PlayerSettingsRepository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy-owned per-player settings. Postgres is the source of truth; Redis is
 * the cache and transport to the backends.
 *
 * <ul>
 *   <li>On proxy login we load a player's settings from Postgres into an
 *       in-memory cache and seed the {@link #HASH_KEY} hash the backends HGET
 *       on join.</li>
 *   <li>Backends publish change requests on {@link #SET_CHANNEL}; we persist
 *       them to Postgres, update the cache + hash, and re-broadcast the
 *       authoritative value on {@link #UPDATE_CHANNEL}.</li>
 *   <li>Proxy-side features (private-message DND) read the cache directly via
 *       {@link #acceptsMessages(UUID)}.</li>
 * </ul>
 *
 * <p>The cached value is the raw settings JSON object, so unknown/new fields the
 * backends add flow through untouched without proxy changes.
 */
public class PlayerSettingsService {

    public static final String HASH_KEY = "crabutilities:settings";
    public static final String SET_CHANNEL = "crabutilities:settings-set";
    public static final String UPDATE_CHANNEL = "crabutilities:settings-updates";
    private static final List<String> ANALYTICS_SETTINGS = List.of(
            "phantoms", "mentionPings", "acceptMessages",
            "locatorBar", "bingoMessages", "coordinateHud");

    private final CrabUtilitiesVelocity plugin;
    private final PlayerSettingsRepository repository;
    private final VelocityConfig config;

    private final ConcurrentHashMap<UUID, JsonObject> cache = new ConcurrentHashMap<>();

    private volatile JedisPool jedisPool;
    private volatile JedisPubSub pubSub;
    private Thread subscriberThread;
    private volatile boolean stopped;

    public PlayerSettingsService(CrabUtilitiesVelocity plugin, PlayerSettingsRepository repository,
                                 VelocityConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = config;
    }

    public void start() {
        // One connection is held by the persistent subscriber; leave headroom
        // for concurrent login seeds and change broadcasts.
        this.jedisPool = RedisPools.create(config, 4);
        startSubscriber();
        plugin.getLogger().info("Player settings service started (Postgres-backed; Redis cache/transport).");
    }

    /** Loads a player's settings from Postgres on login and seeds the cache + Redis. */
    public void onLogin(UUID uuid) {
        plugin.runDatabaseTask("settings-load", () -> {
            JsonObject settings = parseOrEmpty(repository.load(uuid.toString()));
            cache.put(uuid, settings);
            // Seed the hash AND broadcast, so a backend whose join HGET races
            // ahead of this load still converges via the update subscription.
            persistToRedis(uuid, settings, true);
        });
    }

    public void onDisconnect(UUID uuid) {
        cache.remove(uuid);
    }

    /** Proxy-side read: does this player accept private messages? Defaults to true. */
    public boolean acceptsMessages(UUID uuid) {
        JsonObject settings = cache.get(uuid);
        return readBool(settings, "acceptMessages", true);
    }

    public void shutdown() {
        stopped = true;
        if (pubSub != null) {
            try { pubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
            subscriberThread = null;
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
        cache.clear();
    }

    private static JsonObject parseOrEmpty(String json) {
        if (json == null) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static boolean readBool(JsonObject settings, String key, boolean fallback) {
        if (settings == null || !settings.has(key)) {
            return fallback;
        }
        try {
            return settings.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Writes the player's settings to the Redis hash (the join-time cache the
     * backends HGET) and, when {@code broadcast} is set, publishes them on
     * {@link #UPDATE_CHANNEL} so subscribed backends update live. The broadcast
     * envelope is the settings object plus the player's UUID.
     */
    private void persistToRedis(UUID uuid, JsonObject settings, boolean broadcast) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(HASH_KEY, uuid.toString(), settings.toString());
            if (broadcast) {
                JsonObject envelope = settings.deepCopy();
                envelope.addProperty("uuid", uuid.toString());
                jedis.publish(UPDATE_CHANNEL, envelope.toString());
            }
        } catch (Exception e) {
            plugin.getLogger().debug("Failed to write settings to Redis for {}: {}", uuid, e.getMessage());
        }
    }

    /**
     * Handles a change request published by a backend: {@code {uuid, ...fields}}.
     * Persists to Postgres, updates the cache + hash, and re-broadcasts the
     * authoritative state to every server.
     */
    private void handleSetRequest(String payload) {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(payload).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        if (!envelope.has("uuid")) {
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(envelope.get("uuid").getAsString());
        } catch (Exception e) {
            return;
        }

        // The settings object is the envelope minus the transport-only uuid.
        JsonObject settings = envelope.deepCopy();
        settings.remove("uuid");
        String settingsJson = settings.toString();

        JsonObject previous = cache.put(uuid, settings);
        plugin.runDatabaseTask("settings-save", () -> repository.save(uuid.toString(), settingsJson));
        persistToRedis(uuid, settings, true);
        captureSettingChanges(uuid, previous, settings);
    }

    private void captureSettingChanges(UUID uuid, JsonObject previous, JsonObject settings) {
        if (previous == null) return;
        for (String key : ANALYTICS_SETTINGS) {
            JsonElement oldValue = previous.get(key);
            JsonElement newValue = settings.get(key);
            if (Objects.equals(oldValue, newValue)) continue;
            Object analyticsValue = analyticsValue(newValue);
            if (analyticsValue == null) continue;
            plugin.getAnalyticsService().capture(
                    uuid,
                    AnalyticsService.PLAYER_SETTING_CHANGED,
                    Map.of("setting", key, "value", analyticsValue));
        }
    }

    private static Object analyticsValue(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsNumber();
        if (primitive.isString()) return primitive.getAsString();
        return null;
    }

    private void startSubscriber() {
        subscriberThread = new Thread(() -> {
            boolean warned = false;
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) {
                    break;
                }
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Settings Redis subscriber reconnected.");
                        warned = false;
                    }
                    pubSub = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            if (SET_CHANNEL.equals(channel)) {
                                try {
                                    handleSetRequest(message);
                                } catch (Throwable t) {
                                    plugin.getLogger().debug("Settings set handler threw: {}", t.getMessage());
                                }
                            }
                        }
                    };
                    jedis.subscribe(pubSub, SET_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (!warned) {
                        plugin.getLogger().warn("Settings Redis subscriber unavailable; reconnecting in 3s: {}",
                                e.getMessage());
                        warned = true;
                    }
                    try {
                        Thread.sleep(3000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-Settings");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }
}
