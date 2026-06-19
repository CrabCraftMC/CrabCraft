package crabcraft.net.crabUtilities.velocity.mute;

import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

import java.util.Collection;
import java.util.UUID;

/**
 * Writes mute state into Redis and notifies backends of changes.
 *
 * <p>Shared contract with the Spigot side:
 * <ul>
 *   <li>Live cache: hash {@code crabutilities:mutes}, field = player
 *       UUID, value = expiry epoch millis ({@code 0} = permanent).
 *       Absent field means not muted.</li>
 *   <li>Updates: channel {@code crabutilities:mute-updates}, message =
 *       {@code <uuid>\0<expiry>} where expiry is epoch millis,
 *       {@code 0} = permanent, {@code -1} = unmuted.</li>
 * </ul>
 */
public final class RedisMutePublisher {

    public static final String HASH_KEY = "crabutilities:mutes";
    public static final String UPDATE_CHANNEL = "crabutilities:mute-updates";
    private static final String SEPARATOR = "\0";

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private JedisPool jedisPool;

    public RedisMutePublisher(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        try {
            if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
                this.jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                        config.getRedisPort(), 2000, config.getRedisPassword());
            } else {
                this.jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                        config.getRedisPort(), 2000);
            }
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }
            plugin.getLogger().info("Mute publisher connected to Redis at {}:{}",
                    config.getRedisHost(), config.getRedisPort());
        } catch (Exception e) {
            plugin.getLogger().error("Mute publisher failed to connect to Redis at {}:{}",
                    config.getRedisHost(), config.getRedisPort(), e);
            if (jedisPool != null) {
                try { jedisPool.close(); } catch (Exception ignored) {}
            }
            jedisPool = null;
        }
    }

    /** HSET the live cache and publish the update. Runs off the main thread. */
    public void applyMute(UUID uuid, long expiry) {
        if (jedisPool == null) return;
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset(HASH_KEY, uuid.toString(), Long.toString(expiry));
                jedis.publish(UPDATE_CHANNEL, uuid + SEPARATOR + expiry);
            } catch (Exception e) {
                plugin.getLogger().warn("Failed to publish mute for {}", uuid, e);
            }
        }).schedule();
    }

    /** HDEL the live cache and publish an unmute. Runs off the main thread. */
    public void clearMute(UUID uuid) {
        if (jedisPool == null) return;
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hdel(HASH_KEY, uuid.toString());
                jedis.publish(UPDATE_CHANNEL, uuid + SEPARATOR + "-1");
            } catch (Exception e) {
                plugin.getLogger().warn("Failed to publish unmute for {}", uuid, e);
            }
        }).schedule();
    }

    /**
     * Rebuilds the live cache from the authoritative set of active mutes.
     * Stale keys are dropped (the whole hash is deleted first) so a mute
     * removed while the proxy was down doesn't linger. No updates are
     * published — backends HGETALL on their own startup.
     *
     * <p>Synchronous: intended to run once during init.
     */
    public void rehydrate(Collection<MuteStore.Mute> activeMutes) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            pipeline.del(HASH_KEY);
            for (MuteStore.Mute mute : activeMutes) {
                pipeline.hset(HASH_KEY, mute.uuid().toString(), Long.toString(mute.expiry()));
            }
            pipeline.sync();
            plugin.getLogger().info("Rehydrated {} active mute(s) into Redis", activeMutes.size());
        } catch (Exception e) {
            plugin.getLogger().error("Failed to rehydrate mutes into Redis", e);
        }
    }

    public void shutdown() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }
}
