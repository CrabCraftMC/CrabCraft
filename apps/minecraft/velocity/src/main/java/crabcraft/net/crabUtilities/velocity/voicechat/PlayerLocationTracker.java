package crabcraft.net.crabUtilities.velocity.voicechat;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.EventTask;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Velocity-side of the cross-server voice bridge.
 *
 * <p>Writes {@code crabcraft:svc:player-home:&lt;uuid&gt;} as a backend name
 * plus a unique hop token whenever a player connects to or switches
 * backend. Backends validate the route carried by inbound voice frames
 * so that during the brief window when
 * a player is mid-switch, the old backend's "ghost" frames are
 * discarded by the new backend (and any other listening backend).
 *
 * <p>The key is set with a TTL so a crashed proxy doesn't leave stale
 * routing in place; it is refreshed on every hop and while connected.
 */
public class PlayerLocationTracker {

    static final String PLAYER_HOME_KEY_PREFIX = "crabcraft:svc:player-home:";

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private final long ttlSeconds;
    private final String routeInstance = UUID.randomUUID().toString();
    private final AtomicLong routeSequence = new AtomicLong();
    private final Map<UUID, RouteState> routes = new ConcurrentHashMap<>();
    private final Map<UUID, Object> playerLocks = new ConcurrentHashMap<>();

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
        long refreshSeconds = Math.max(10L, Math.min(30L, ttlSeconds / 3));
        refreshTask = plugin.getServer().getScheduler()
                .buildTask(plugin, this::refreshAllHomes)
                .delay(Duration.ofSeconds(refreshSeconds))
                .repeat(Duration.ofSeconds(refreshSeconds))
                .schedule();
        refreshAllHomes();

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
        routes.clear();
        playerLocks.clear();
    }

    private void refreshAllHomes() {
        for (Player player : plugin.getServer().getAllPlayers()) {
            refreshHome(player);
        }
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String backend = event.getServer().getServerInfo().getName();
        return EventTask.async(() -> {
            if (jedisPool != null) updateConnectedHome(player, backend);
        });
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        // DisconnectEvent only fires when the player leaves the proxy
        // entirely (NOT on backend hop). Clear routing immediately, but
        // retain the short player-group lease so a quick relog can restore.
        return EventTask.async(() -> {
            if (jedisPool == null) return;
            // Await cleanup as part of the disconnect event so a later login
            // cannot race an untracked fire-and-forget deletion.
            deleteDisconnectedSession(lockFor(playerId), player,
                    () -> plugin.getServer().getPlayer(playerId).orElse(null),
                    () -> {
                        routes.remove(playerId);
                        deleteHome(playerId);
                    });
        });
    }

    private void updateConnectedHome(Player player, String expectedBackend) {
        UUID playerId = player.getUniqueId();
        updateCurrentSession(lockFor(playerId), player,
                () -> plugin.getServer().getPlayer(playerId).orElse(null),
                current -> current.getCurrentServer().ifPresent(server ->
                        writeCurrentRoute(current, expectedBackend,
                                server.getServerInfo().getName(), 3)));
    }

    private void refreshHome(Player player) {
        UUID playerId = player.getUniqueId();
        updateCurrentSession(lockFor(playerId), player,
                () -> plugin.getServer().getPlayer(playerId).orElse(null),
                current -> current.getCurrentServer().ifPresent(server -> {
                    String backend = server.getServerInfo().getName();
                    writeCurrentRoute(current, backend, backend, 1);
                }));
    }

    private void writeCurrentRoute(Player player, String expectedBackend,
                                   String currentBackend, int attempts) {
        if (!expectedBackend.equals(currentBackend)) return;
        UUID playerId = player.getUniqueId();
        RouteState route = routes.get(playerId);
        if (route == null || route.session() != player || !route.backend().equals(currentBackend)) {
            route = new RouteState(player, currentBackend,
                    routeValue(currentBackend, routeInstance, routeSequence.incrementAndGet()));
            routes.put(playerId, route);
        }
        writeHome(playerId, route.value(), attempts);
    }

    static String routeValue(String backend, String instance, long sequence) {
        return backend + "\0" + instance + ":" + sequence;
    }

    private void writeHome(UUID playerId, String route, int attempts) {
        String key = "crabcraft:svc:player-home:" + playerId;
        runRedis("write player home", attempts,
                jedis -> jedis.set(key, route, SetParams.setParams().ex(ttlSeconds)));
    }

    private void deleteHome(UUID playerId) {
        runRedis("delete player home", 3,
                jedis -> jedis.del("crabcraft:svc:player-home:" + playerId));
    }

    private void runRedis(String operation, int attempts, Consumer<Jedis> command) {
        Exception failure = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            JedisPool pool = jedisPool;
            if (pool == null || pool.isClosed()) return;
            try (Jedis jedis = pool.getResource()) {
                command.accept(jedis);
                return;
            } catch (Exception e) {
                failure = e;
                if (attempt + 1 < attempts) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        if (failure != null) {
            plugin.getLogger().debug("Failed to {}: {}", operation, failure.getMessage());
        }
    }

    static <T> void updateCurrentSession(Object lock, T expectedSession,
                                         Supplier<T> currentSession, Consumer<T> update) {
        synchronized (lock) {
            T current = currentSession.get();
            // Player identity distinguishes a quick relog that reuses the UUID.
            if (current == expectedSession) update.accept(current);
        }
    }

    static <T> void deleteDisconnectedSession(Object lock, T disconnectedSession,
                                               Supplier<T> currentSession, Runnable delete) {
        synchronized (lock) {
            T current = currentSession.get();
            if (current == null || current == disconnectedSession) delete.run();
        }
    }

    private Object lockFor(UUID playerId) {
        return playerLocks.computeIfAbsent(playerId, ignored -> new Object());
    }

    /**
     * Returns the exact Redis route owned by this proxy connection. The
     * identity check prevents a delayed call task from adopting a quick
     * relog's route merely because it has the same UUID.
     */
    RouteSnapshot currentRoute(Player expectedSession) {
        UUID playerId = expectedSession.getUniqueId();
        synchronized (lockFor(playerId)) {
            Player current = plugin.getServer().getPlayer(playerId).orElse(null);
            RouteState route = routes.get(playerId);
            if (current != expectedSession || route == null || route.session() != expectedSession) {
                return null;
            }
            return new RouteSnapshot(expectedSession, route.value());
        }
    }

    static String playerHomeKey(UUID playerId) {
        return PLAYER_HOME_KEY_PREFIX + playerId;
    }

    private record RouteState(Player session, String backend, String value) {}

    record RouteSnapshot(Player session, String value) {}
}
