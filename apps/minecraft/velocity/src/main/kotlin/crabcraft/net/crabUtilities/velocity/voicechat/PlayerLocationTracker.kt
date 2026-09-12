package crabcraft.net.crabUtilities.velocity.voicechat

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.scheduler.ScheduledTask
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.RedisPools
import crabcraft.net.crabUtilities.velocity.VelocityConfig
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.params.SetParams
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Writes a backend name and unique hop token for each connected player. Backends validate
 * inbound voice routes so an old backend's ghost frames cannot survive a server switch.
 * The route has a TTL and is refreshed on every hop and while connected.
 */
open class PlayerLocationTracker(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig) {
    private val ttlSeconds = Math.max(30L, config.getVoicechatPlayerHomeTtlSeconds())
    private val routeInstance = UUID.randomUUID().toString()
    private val routeSequence = AtomicLong()
    private val routes = ConcurrentHashMap<UUID, RouteState>()
    private val playerLocks = ConcurrentHashMap<UUID, Any>()
    private var jedisPool: JedisPool? = null
    private var refreshTask: ScheduledTask? = null

    open fun start() {
        val poolConfig = JedisPoolConfig()
        poolConfig.maxTotal = 2
        // Bound resource waits so a Redis hiccup can't hang scheduler tasks.
        poolConfig.setMaxWait(Duration.ofMillis(1500))
        jedisPool = RedisPools.create(config, poolConfig)
        // Refresh within the TTL even for players who remain on one backend.
        val refreshSeconds = Math.max(10L, Math.min(30L, ttlSeconds / 3))
        refreshTask = plugin.getServer().scheduler.buildTask(plugin, Runnable { refreshAllHomes() })
            .delay(Duration.ofSeconds(refreshSeconds)).repeat(Duration.ofSeconds(refreshSeconds)).schedule()
        refreshAllHomes()
        plugin.getLogger().info("Voice location tracker started (TTL {}s, refresh {}s); Redis will be retried on player updates.", ttlSeconds, refreshSeconds)
    }
    open fun shutdown() {
        refreshTask?.cancel(); refreshTask = null
        val pool = jedisPool
        if (pool != null && !pool.isClosed) {
            try { pool.close() } catch (_: NoClassDefFoundError) {}
            jedisPool = null
        }
        routes.clear(); playerLocks.clear()
    }
    private fun refreshAllHomes() { for (player in plugin.getServer().allPlayers) refreshHome(player) }
    @Subscribe(order = PostOrder.LATE)
    open fun onServerConnected(event: ServerPostConnectEvent): EventTask {
        val player = event.player
        // ServerPostConnectEvent runs after getCurrentServer() is committed.
        return EventTask.async { if (jedisPool != null) updateConnectedHome(player) }
    }
    @Subscribe(order = PostOrder.LATE)
    open fun onDisconnect(event: DisconnectEvent): EventTask {
        val player = event.player
        val playerId = player.uniqueId
        // Await cleanup so a later login cannot race an untracked deletion.
        return EventTask.async {
            if (jedisPool == null) return@async
            deleteDisconnectedSession(lockFor(playerId), player,
                Supplier { plugin.getServer().getPlayer(playerId).orElse(null) },
                Runnable { routes.remove(playerId); deleteHome(playerId) })
        }
    }
    private fun updateConnectedHome(player: Player) {
        val playerId = player.uniqueId
        updateCurrentSession(lockFor(playerId), player, Supplier { plugin.getServer().getPlayer(playerId).orElse(null) },
            Consumer { current -> current.currentServer.ifPresent { server -> writeCurrentRoute(current, server.serverInfo.name, 3) } })
    }
    private fun refreshHome(player: Player) {
        val playerId = player.uniqueId
        updateCurrentSession(lockFor(playerId), player, Supplier { plugin.getServer().getPlayer(playerId).orElse(null) },
            Consumer { current -> current.currentServer.ifPresent { server -> writeCurrentRoute(current, server.serverInfo.name, 1) } })
    }
    private fun writeCurrentRoute(player: Player, currentBackend: String, attempts: Int) {
        val playerId = player.uniqueId
        var route = routes[playerId]
        if (route == null || route.session !== player || route.backend != currentBackend) {
            route = RouteState(player, currentBackend, routeValue(currentBackend, routeInstance, routeSequence.incrementAndGet()))
            routes[playerId] = route
        }
        writeHome(playerId, route.value, attempts)
    }
    private fun writeHome(playerId: UUID, route: String, attempts: Int) {
        val key = "crabcraft:svc:player-home:" + playerId
        runRedis("write player home", attempts, Consumer { jedis -> jedis.set(key, route, SetParams.setParams().ex(ttlSeconds)) })
    }
    private fun deleteHome(playerId: UUID) { runRedis("delete player home", 3, Consumer { jedis -> jedis.del("crabcraft:svc:player-home:" + playerId) }) }
    private fun runRedis(operation: String, attempts: Int, command: Consumer<Jedis>) {
        var failure: Exception? = null
        for (attempt in 0 until attempts) {
            val pool = jedisPool
            if (pool == null || pool.isClosed) return
            try { pool.resource.use { jedis -> command.accept(jedis); return } }
            catch (e: Exception) {
                failure = e
                if (attempt + 1 < attempts) {
                    try { Thread.sleep(100L) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                }
            }
        }
        if (failure != null) plugin.getLogger().debug("Failed to {}: {}", operation, failure.message)
    }
    private fun lockFor(playerId: UUID): Any = playerLocks.computeIfAbsent(playerId) { Any() }
    /** The identity check stops a delayed task adopting a quick relog's route. */
    fun currentRoute(expectedSession: Player): RouteSnapshot? {
        val playerId = expectedSession.uniqueId
        synchronized(lockFor(playerId)) {
            val current = plugin.getServer().getPlayer(playerId).orElse(null)
            val route = routes[playerId]
            if (current !== expectedSession || route == null || route.session !== expectedSession) return null
            return RouteSnapshot(expectedSession, route.value)
        }
    }
    private data class RouteState(val session: Player, val backend: String, val value: String)
    data class RouteSnapshot(private val session: Player, private val value: String) {
        fun session() = session
        fun value() = value
    }
    companion object {
        const val PLAYER_HOME_KEY_PREFIX = "crabcraft:svc:player-home:"
        @JvmStatic fun routeValue(backend: String, instance: String, sequence: Long) = backend + "\u0000" + instance + ":" + sequence
        @JvmStatic fun <T : Any> updateCurrentSession(lock: Any, expectedSession: T, currentSession: Supplier<T?>, update: Consumer<T>) {
            synchronized(lock) { val current = currentSession.get(); if (current === expectedSession) update.accept(current) }
        }
        @JvmStatic fun <T : Any> deleteDisconnectedSession(lock: Any, disconnectedSession: T, currentSession: Supplier<T?>, delete: Runnable) {
            synchronized(lock) { val current = currentSession.get(); if (current == null || current === disconnectedSession) delete.run() }
        }
        @JvmStatic fun playerHomeKey(playerId: UUID) = PLAYER_HOME_KEY_PREFIX + playerId
    }
}
