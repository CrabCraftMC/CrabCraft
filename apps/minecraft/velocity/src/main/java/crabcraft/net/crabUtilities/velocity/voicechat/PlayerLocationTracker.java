package crabcraft.net.crabUtilities.velocity.voicechat;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.util.UUID;

/**
 * Velocity-side of the cross-server voice bridge.
 *
 * <p>Writes {@code crabcraft:svc:player-home:&lt;uuid&gt;} = backend name
 * to Redis whenever a player connects to or switches between backend
 * servers. Backends use this key to validate the {@code homeBackend}
 * field on inbound voice frames so that during the brief window when
 * a player is mid-switch, the old backend's "ghost" frames are
 * discarded by the new backend (and any other listening backend).
 *
 * <p>The key is set with a TTL so a crashed proxy doesn't leave stale
 * routing in place; the TTL is refreshed on every server hop and is
 * comfortably longer than any real switch window.
 */
public class PlayerLocationTracker {

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private final long ttlSeconds;

    private JedisPool jedisPool;

    public PlayerLocationTracker(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.ttlSeconds = Math.max(30L, config.getVoicechatPlayerHomeTtlSeconds());
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000);
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            plugin.getLogger().info("Voice location tracker connected to Redis (TTL {}s)", ttlSeconds);
        } catch (Exception e) {
            plugin.getLogger().error("Voice location tracker failed to connect to Redis", e);
            jedisPool.close();
            jedisPool = null;
        }
    }

    public void shutdown() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }

    @Subscribe(order = PostOrder.LATE)
    public void onServerConnected(ServerConnectedEvent event) {
        if (jedisPool == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        String backend = event.getServer().getServerInfo().getName();
        plugin.getServer().getScheduler().buildTask(plugin, () -> writeHome(playerId, backend)).schedule();
    }

    @Subscribe(order = PostOrder.LATE)
    public void onDisconnect(DisconnectEvent event) {
        if (jedisPool == null) return;
        UUID playerId = event.getPlayer().getUniqueId();
        plugin.getServer().getScheduler().buildTask(plugin, () -> deleteHome(playerId)).schedule();
    }

    private void writeHome(UUID playerId, String backend) {
        String key = "crabcraft:svc:player-home:" + playerId;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, backend, SetParams.setParams().ex(ttlSeconds));
        } catch (Exception e) {
            plugin.getLogger().debug("Failed to write player home: " + e.getMessage());
        }
    }

    private void deleteHome(UUID playerId) {
        String key = "crabcraft:svc:player-home:" + playerId;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
        } catch (Exception ignored) {}
    }
}
