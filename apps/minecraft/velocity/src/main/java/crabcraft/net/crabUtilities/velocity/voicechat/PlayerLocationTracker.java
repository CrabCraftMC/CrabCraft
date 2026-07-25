package crabcraft.net.crabUtilities.velocity.voicechat;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.RedisPools;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
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
    private ScheduledTask refreshTask;

    public PlayerLocationTracker(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.ttlSeconds = Math.max(30L, config.getVoicechatPlayerHomeTtlSeconds());
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        // Bound resource waits so a Redis hiccup can't hang scheduler tasks.
        poolConfig.setMaxWait(Duration.ofMillis(1500));
        jedisPool = RedisPools.create(config, poolConfig);

        // Refresh every connected player's home key well inside the TTL.
        // Keys used to be written only on server hops, so they expired for
        // anyone who stayed on one backend longer than the TTL — silently
        // disabling origin validation until their next hop.
        long refreshSeconds = Math.max(30L, ttlSeconds / 3);
        refreshTask = plugin.getServer().getScheduler()
                .buildTask(plugin, this::refreshAllHomes)
                .delay(Duration.ofSeconds(refreshSeconds))
                .repeat(Duration.ofSeconds(refreshSeconds))
                .schedule();

        plugin.getLogger().info("Voice location tracker started (TTL {}s, refresh {}s); "
                + "Redis will be retried on player updates.", ttlSeconds, refreshSeconds);
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }

    private void refreshAllHomes() {
        for (Player player : plugin.getServer().getAllPlayers()) {
            player.getCurrentServer().ifPresent(server ->
                    writeHome(player.getUniqueId(), server.getServerInfo().getName()));
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
        // DisconnectEvent only fires when the player leaves the proxy
        // entirely (NOT on backend hop), so this is the right place to
        // clear the auto-rejoin record. Server hops keep the
        // player-group key intact via the 90s TTL.
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            // A quick relog can run this delete unordered against the new
            // session's writeHome. If the player is already back on the
            // proxy, keep their fresh keys.
            if (plugin.getServer().getPlayer(playerId).isPresent()) return;
            deletePlayerState(playerId);
        }).schedule();
    }

    private void writeHome(UUID playerId, String backend) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        String key = "crabcraft:svc:player-home:" + playerId;
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, backend, SetParams.setParams().ex(ttlSeconds));
        } catch (Exception e) {
            plugin.getLogger().debug("Failed to write player home: " + e.getMessage());
        }
    }

    private void deletePlayerState(UUID playerId) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.del(
                    "crabcraft:svc:player-home:" + playerId,
                    "crabcraft:svc:player-group:" + playerId);
        } catch (Exception ignored) {}
    }
}
