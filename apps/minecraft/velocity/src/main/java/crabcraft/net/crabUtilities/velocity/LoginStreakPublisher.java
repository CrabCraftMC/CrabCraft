package crabcraft.net.crabUtilities.velocity;

import com.google.gson.JsonObject;
import crabcraft.net.crabUtilities.velocity.db.LoginStreakService;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Mirrors login streak snapshots into Redis so backend Spigot servers
 * can render them through PlaceholderAPI without each one talking to
 * Postgres.
 *
 * <p>Two writes per recorded login:
 * <ul>
 *   <li>{@code HSET crabutilities:streaks &lt;uuid&gt; &lt;json&gt;} —
 *       authoritative cache the backends seed from on player join.</li>
 *   <li>{@code PUBLISH crabutilities:streaks-updates &lt;json&gt;} —
 *       live notification for backends to refresh their in-memory
 *       cache on the spot.</li>
 * </ul>
 *
 * <p>The published payload always includes the player UUID so a single
 * subscription handles all players.
 */
public class LoginStreakPublisher {

    public static final String HASH_KEY = "crabutilities:streaks";
    public static final String UPDATE_CHANNEL = "crabutilities:streaks-updates";

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private JedisPool jedisPool;
    private volatile boolean redisFailureLogged;

    public LoginStreakPublisher(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
        connect();
    }

    private void connect() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            this.jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000);
        }
        plugin.getLogger().info("Login streak publisher ready; Redis will be retried on publish if unavailable.");
    }

    public void publish(String uuid, LoginStreakService.StreakSnapshot snapshot, int resetHourUtc) {
        if (jedisPool == null || jedisPool.isClosed() || snapshot == null) return;

        long now = System.currentTimeMillis() / 1000L;
        long expiresAt = LoginStreakService.expiryOf(snapshot.lastLoginAt, resetHourUtc);
        boolean active = now < expiresAt;

        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid);
        payload.addProperty("current_streak", active ? snapshot.currentStreak : 0);
        payload.addProperty("pending_streak", snapshot.currentStreak);
        payload.addProperty("longest_streak", snapshot.longestStreak);
        payload.addProperty("last_login_at", snapshot.lastLoginAt);
        payload.addProperty("streak_started_at", snapshot.streakStartedAt);
        payload.addProperty("expires_at", expiresAt);
        payload.addProperty("active", active);

        String body = payload.toString();
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(HASH_KEY, uuid, body);
            jedis.publish(UPDATE_CHANNEL, body);
            if (redisFailureLogged) {
                plugin.getLogger().info("Login streak Redis publisher recovered.");
                redisFailureLogged = false;
            }
        } catch (Exception e) {
            if (!redisFailureLogged) {
                plugin.getLogger().warn("Failed to publish login streak for {}; will retry on later logins", uuid, e);
                redisFailureLogged = true;
            } else {
                plugin.getLogger().debug("Failed to publish login streak for {}: {}", uuid, e.getMessage());
            }
        }
    }

    public void shutdown() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }
}
