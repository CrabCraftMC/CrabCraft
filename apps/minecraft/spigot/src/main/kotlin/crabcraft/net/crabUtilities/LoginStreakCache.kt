package crabcraft.net.crabUtilities

import com.google.gson.JsonParser
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory mirror of the Velocity-owned login streak data. Join-time Redis
 * reads and a persistent subscription warm the cache; placeholders perform no I/O.
 */
open class LoginStreakCache(private val plugin: CrabUtilities) : Listener {
    private val redisHost = plugin.getConfig().getString("redis.host", "localhost")
    private val redisPort = plugin.getConfig().getInt("redis.port", 6379)
    private val redisPassword = plugin.getConfig().getString("redis.password", "")
    private val cache = ConcurrentHashMap<UUID, StreakSnapshot>()
    private var jedisPool: JedisPool? = null
    private var subscriberThread: SubscriberThread? = null

    open fun start() {
        val poolConfig = JedisPoolConfig()
        poolConfig.setMaxTotal(2)
        jedisPool = if (!redisPassword.isNullOrEmpty()) {
            JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword)
        } else {
            JedisPool(poolConfig, redisHost, redisPort, 2000)
        }
        primeOnlinePlayers()
        subscriberThread = SubscriberThread().also {
            it.name = "crabutilities-streak-subscriber"
            it.isDaemon = true
            it.start()
        }
        plugin.getLogger().info("Login streak cache started; Redis will be retried asynchronously if unavailable.")
    }

    open fun shutdown() {
        subscriberThread?.let { thread ->
            thread.cancelled = true
            try {
                thread.subscriber?.let { if (it.isSubscribed) it.unsubscribe() }
            } catch (ignored: Exception) {}
            thread.interrupt()
            subscriberThread = null
        }
        jedisPool?.let { pool ->
            if (!pool.isClosed) {
                try { pool.close() } catch (ignored: NoClassDefFoundError) {}
                jedisPool = null
            }
        }
    }

    open fun get(uuid: UUID): StreakSnapshot? = cache[uuid]

    @EventHandler
    open fun onPlayerJoin(event: PlayerJoinEvent) {
        val uuid = event.getPlayer().getUniqueId()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { refreshOne(uuid) })
    }

    @EventHandler
    open fun onPlayerQuit(event: PlayerQuitEvent) {
        // Drop offline players; the next login re-warms the cache.
        cache.remove(event.getPlayer().getUniqueId())
    }

    private fun primeOnlinePlayers() {
        val capture = Runnable {
            val onlinePlayers = Bukkit.getOnlinePlayers().map { it.getUniqueId() }
            if (onlinePlayers.isNotEmpty()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { primeFromHash(onlinePlayers) })
            }
        }
        if (Bukkit.isPrimaryThread()) capture.run() else Bukkit.getScheduler().runTask(plugin, capture)
    }

    private fun primeFromHash(onlinePlayers: List<UUID>) {
        val pool = jedisPool ?: return
        try {
            pool.resource.use { jedis ->
                for (uuid in onlinePlayers) jedis.hget(HASH_KEY, uuid.toString())?.let { ingest(it) }
            }
        } catch (e: Exception) {
            plugin.getLogger().fine("Failed to prime login streak cache: " + e.message)
        }
    }

    private fun refreshOne(uuid: UUID) {
        val pool = jedisPool ?: return
        try {
            pool.resource.use { jedis -> jedis.hget(HASH_KEY, uuid.toString())?.let { ingest(it) } }
        } catch (e: Exception) {
            plugin.getLogger().fine("Failed to load streak for " + uuid + ": " + e.message)
        }
    }

    private fun ingest(json: String) {
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            val uuid = UUID.fromString(obj.get("uuid").asString)
            val current = if (obj.has("current_streak")) obj.get("current_streak").asInt else 0
            val pending = if (obj.has("pending_streak")) obj.get("pending_streak").asInt else current
            val longest = if (obj.has("longest_streak")) obj.get("longest_streak").asInt else 0
            val lastLogin = if (obj.has("last_login_at")) obj.get("last_login_at").asLong else 0L
            val startedAt = if (obj.has("streak_started_at")) obj.get("streak_started_at").asLong else lastLogin
            val expiresAt = if (obj.has("expires_at")) obj.get("expires_at").asLong else 0L
            val active = if (obj.has("active")) obj.get("active").asBoolean else current > 0
            val incoming = StreakSnapshot(current, pending, longest, lastLogin, startedAt, expiresAt, active)
            cache.merge(uuid, incoming, ::preferNewerSnapshot)
        } catch (e: Exception) {
            plugin.getLogger().fine("Bad streak update payload: " + e.message)
        }
    }

    open fun snapshot(): Map<UUID, StreakSnapshot> = java.util.Map.copyOf(cache)

    class StreakSnapshot(
        @JvmField val currentStreak: Int,
        @JvmField val pendingStreak: Int,
        @JvmField val longestStreak: Int,
        @JvmField val lastLoginAt: Long,
        @JvmField val streakStartedAt: Long,
        @JvmField val expiresAt: Long,
        @JvmField val active: Boolean
    ) {
        /** Rechecks expiry because the payload's active/current fields can go stale. */
        fun isActiveAt(nowEpochSeconds: Long): Boolean = pendingStreak > 0 && nowEpochSeconds < expiresAt
        fun currentStreakAt(nowEpochSeconds: Long): Int = if (isActiveAt(nowEpochSeconds)) pendingStreak else 0
    }

    private inner class SubscriberThread : Thread() {
        @Volatile var cancelled = false
        @Volatile var subscriber: JedisPubSub? = null

        override fun run() {
            // A subscription blocks until its connection drops; retry with backoff.
            var backoffMs = 1000L
            var warned = false
            while (!cancelled) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) return
                try {
                    pool.resource.use { jedis ->
                        if (warned) {
                            plugin.getLogger().info("Login streak Redis subscription reconnected.")
                            warned = false
                            primeOnlinePlayers()
                        }
                        subscriber = object : JedisPubSub() {
                            override fun onMessage(channel: String, message: String) {
                                if (UPDATE_CHANNEL == channel) ingest(message)
                            }
                        }
                        backoffMs = 1000L
                        jedis.subscribe(subscriber, UPDATE_CHANNEL)
                    }
                } catch (e: Exception) {
                    if (cancelled) return
                    if (!warned) {
                        plugin.getLogger().warning("Login streak Redis subscription unavailable; retrying: " + e.message)
                        warned = true
                    } else {
                        plugin.getLogger().fine("Streak subscription dropped: " + e.message)
                    }
                    try { sleep(backoffMs) } catch (ie: InterruptedException) { return }
                    backoffMs = minOf(30_000L, backoffMs * 2L)
                }
            }
        }
    }

    companion object {
        const val HASH_KEY = "crabutilities:streaks"
        const val UPDATE_CHANNEL = "crabutilities:streaks-updates"

        @JvmStatic
        fun preferNewerSnapshot(cached: StreakSnapshot, incoming: StreakSnapshot): StreakSnapshot =
            if (incoming.lastLoginAt >= cached.lastLoginAt) incoming else cached
    }
}
