package crabcraft.net.crabUtilities.velocity.voicechat

import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.scheduler.ScheduledTask
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.RedisPools
import crabcraft.net.crabUtilities.velocity.VelocityConfig
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.Supplier
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.params.SetParams

/**
 * Writes a backend name and unique hop token for each proxy connection. Backends validate inbound voice routes to
 * discard an old backend's frames during a hop. Routes expire after a proxy crash and are refreshed while players stay
 * connected.
 */
open class PlayerLocationTracker(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig) {
    private val ttlSeconds = maxOf(30L, config.getVoicechatPlayerHomeTtlSeconds())
    private val routeInstance = UUID.randomUUID().toString()
    private val routeSequence = AtomicLong()
    private val routes = ConcurrentHashMap<UUID, RouteState>()
    private val playerLocks = ConcurrentHashMap<UUID, Any>()
    private var jedisPool: JedisPool? = null
    private var refreshTask: ScheduledTask? = null

    open fun start() {
        val poolConfig =
            JedisPoolConfig().apply {
                maxTotal = 2
                // Bound resource waits so a Redis hiccup cannot hang scheduler tasks.
                setMaxWait(Duration.ofMillis(1500))
            }
        jedisPool = RedisPools.create(config, poolConfig)
        // Keep stable connections valid even when no server hop refreshes their key.
        val refreshSeconds = maxOf(10L, minOf(30L, ttlSeconds / 3))
        refreshTask =
            plugin
                .getServer()
                .scheduler
                .buildTask(plugin, Runnable { refreshAllHomes() })
                .delay(Duration.ofSeconds(refreshSeconds))
                .repeat(Duration.ofSeconds(refreshSeconds))
                .schedule()
        refreshAllHomes()
        plugin
            .getLogger()
            .info(
                "Voice location tracker started (TTL {}s, refresh {}s); " + "Redis will be retried on player updates.",
                ttlSeconds,
                refreshSeconds,
            )
    }

    open fun shutdown() {
        refreshTask?.cancel()
        refreshTask = null
        val pool = jedisPool
        if (pool != null && !pool.isClosed) {
            try {
                pool.close()
            } catch (_: NoClassDefFoundError) {}
            jedisPool = null
        }
        routes.clear()
        playerLocks.clear()
    }

    private fun refreshAllHomes() {
        for (player in plugin.getServer().allPlayers) updateConnectedHome(player, 1)
    }

    @Subscribe(order = PostOrder.LATE)
    open fun onServerConnected(event: ServerPostConnectEvent): EventTask {
        val player = event.player
        // This event runs after getCurrentServer() is committed, unlike ServerConnectedEvent.
        return EventTask.async { if (jedisPool != null) updateConnectedHome(player, 3) }
    }

    @Subscribe(order = PostOrder.LATE)
    open fun onDisconnect(event: DisconnectEvent): EventTask {
        val player = event.player
        val playerId = player.uniqueId
        // Await deletion during disconnect so a later login cannot race fire-and-forget cleanup.
        // Keep the player-group lease so a quick relog can restore its group.
        return EventTask.async {
            if (jedisPool != null) {
                deleteDisconnectedSession(
                    lockFor(playerId),
                    player,
                    { plugin.getServer().getPlayer(playerId).orElse(null) },
                    {
                        routes.remove(playerId)
                        deleteHome(playerId)
                    },
                )
            }
        }
    }

    private fun updateConnectedHome(player: Player, attempts: Int) {
        val playerId = player.uniqueId
        updateCurrentSession(
            lockFor(playerId),
            player,
            { plugin.getServer().getPlayer(playerId).orElse(null) },
            { current ->
                current.currentServer.ifPresent { server ->
                    writeCurrentRoute(current, server.serverInfo.name, attempts)
                }
            },
        )
    }

    private fun writeCurrentRoute(player: Player, currentBackend: String, attempts: Int) {
        val playerId = player.uniqueId
        var route = routes[playerId]
        if (route == null || route.session !== player || route.backend != currentBackend) {
            route =
                RouteState(
                    player,
                    currentBackend,
                    routeValue(currentBackend, routeInstance, routeSequence.incrementAndGet()),
                )
            routes[playerId] = route
        }
        writeHome(playerId, route.value, attempts)
    }

    private fun writeHome(playerId: UUID, route: String, attempts: Int) {
        val key = playerHomeKey(playerId)
        runRedis("write player home", attempts) { it.set(key, route, SetParams.setParams().ex(ttlSeconds)) }
    }

    private fun deleteHome(playerId: UUID) {
        runRedis("delete player home", 3) { it.del(playerHomeKey(playerId)) }
    }

    private fun runRedis(operation: String, attempts: Int, command: Consumer<Jedis>) {
        var failure: Exception? = null
        repeat(attempts) { attempt ->
            val pool = jedisPool ?: return
            if (pool.isClosed) return
            try {
                pool.resource.use { command.accept(it) }
                return
            } catch (e: Exception) {
                failure = e
                if (attempt + 1 < attempts) {
                    try {
                        Thread.sleep(100L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        }
        failure?.let { plugin.getLogger().debug("Failed to {}: {}", operation, it.message) }
    }

    private fun lockFor(playerId: UUID): Any = playerLocks.computeIfAbsent(playerId) { Any() }

    /** Returns only the route owned by this exact proxy connection, never a quick relog's route. */
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
        fun session(): Player = session

        fun value(): String = value
    }

    companion object {
        const val PLAYER_HOME_KEY_PREFIX = "crabcraft:svc:player-home:"

        @JvmStatic
        fun routeValue(backend: String, instance: String, sequence: Long): String = "$backend\u0000$instance:$sequence"

        @JvmStatic fun playerHomeKey(playerId: UUID): String = PLAYER_HOME_KEY_PREFIX + playerId

        @JvmStatic
        fun <T : Any> updateCurrentSession(
            lock: Any,
            expectedSession: T,
            currentSession: Supplier<T?>,
            update: Consumer<T>,
        ) {
            synchronized(lock) {
                val current = currentSession.get()
                // Object identity distinguishes a quick relog that reuses the UUID.
                if (current === expectedSession) update.accept(current)
            }
        }

        @JvmStatic
        fun <T : Any> deleteDisconnectedSession(
            lock: Any,
            disconnectedSession: T,
            currentSession: Supplier<T?>,
            delete: Runnable,
        ) {
            synchronized(lock) {
                val current = currentSession.get()
                if (current == null || current === disconnectedSession) delete.run()
            }
        }
    }
}
