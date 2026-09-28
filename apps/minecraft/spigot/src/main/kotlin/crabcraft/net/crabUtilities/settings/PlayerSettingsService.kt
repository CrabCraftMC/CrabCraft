package crabcraft.net.crabUtilities.settings

import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.CrabUtilities
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub

/**
 * Backend mirror of per-player preferences. The proxy owns Postgres and the Redis hash. Hot-path reads use the cache;
 * updates take effect locally and publish a persistence request.
 */
open class PlayerSettingsService(private val plugin: CrabUtilities) : Listener {
    private val redisHost = plugin.getConfig().getString("redis.host", "localhost")
    private val redisPort = plugin.getConfig().getInt("redis.port", 6379)
    private val redisPassword = plugin.getConfig().getString("redis.password", "")
    private val cache = ConcurrentHashMap<UUID, PlayerSettings>()
    private val listeners = CopyOnWriteArrayList<SettingsListener>()
    @Volatile private var jedisPool: JedisPool? = null
    private var subscriberThread: SubscriberThread? = null
    @Volatile private var redisFailureLogged = false

    @java.lang.FunctionalInterface
    fun interface SettingsListener {
        fun onSettingsChanged(uuid: UUID, settings: PlayerSettings)
    }

    open fun addListener(listener: SettingsListener) {
        listeners.add(listener)
    }

    open fun start() {
        // Reserve one connection for subscription, with headroom for join reads and updates.
        val poolConfig = JedisPoolConfig().apply { maxTotal = 4 }
        jedisPool =
            if (!redisPassword.isNullOrEmpty()) {
                JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword)
            } else {
                JedisPool(poolConfig, redisHost, redisPort, 2000)
            }
        primeOnlinePlayers()
        val thread = SubscriberThread()
        subscriberThread = thread
        thread.name = "crabutilities-settings-subscriber"
        thread.isDaemon = true
        thread.start()
        plugin.getLogger().info("Player settings service started; Redis will be retried asynchronously if unavailable.")
    }

    open fun shutdown() {
        subscriberThread?.let { thread ->
            thread.cancelled = true
            try {
                thread.subscriber?.let { if (it.isSubscribed) it.unsubscribe() }
            } catch (_: Exception) {}
            thread.interrupt()
            subscriberThread = null
        }
        jedisPool?.let { pool ->
            if (!pool.isClosed) {
                try {
                    pool.close()
                } catch (_: NoClassDefFoundError) {}
                jedisPool = null
            }
        }
        listeners.clear()
        cache.clear()
    }

    /** Applies the proxy's {uuid, ...fields} broadcast to the cache. */
    private fun ingest(message: String) {
        try {
            val obj = JsonParser.parseString(message).asJsonObject
            val uuid = UUID.fromString(obj.get("uuid").asString)
            val settings = PlayerSettings.fromJson(obj)
            cache[uuid] = settings
            notifySettingsChanged(uuid, settings)
        } catch (e: Exception) {
            plugin.getLogger().fine("Bad settings update payload: ${e.message}")
        }
    }

    open fun get(uuid: UUID): PlayerSettings = cache.getOrDefault(uuid, PlayerSettings.DEFAULTS)

    open fun getPhantomMode(uuid: UUID) = get(uuid).getPhantomMode()

    open fun isMentionPingsEnabled(uuid: UUID) = get(uuid).isMentionPings()

    open fun isAcceptingMessages(uuid: UUID) = get(uuid).isAcceptMessages()

    open fun isLocatorBarEnabled(uuid: UUID) = get(uuid).isLocatorBar()

    open fun isBingoMessagesEnabled(uuid: UUID) = get(uuid).isBingoMessages()

    open fun isCoordinateHudEnabled(uuid: UUID) = get(uuid).isCoordinateHud()

    /** Screens must wait until the stored record or an unavailable-Redis fallback is resolved. */
    open fun isLoaded(uuid: UUID) = cache.containsKey(uuid)

    open fun setPhantomMode(uuid: UUID, mode: PhantomMode?) = update(uuid) { it.withPhantomMode(mode) }

    open fun setMentionPings(uuid: UUID, value: Boolean) = update(uuid) { it.withMentionPings(value) }

    open fun setAcceptingMessages(uuid: UUID, value: Boolean) = update(uuid) { it.withAcceptMessages(value) }

    open fun setLocatorBar(uuid: UUID, value: Boolean) = update(uuid) { it.withLocatorBar(value) }

    open fun setBingoMessages(uuid: UUID, value: Boolean) = update(uuid) { it.withBingoMessages(value) }

    open fun setCoordinateHud(uuid: UUID, value: Boolean) = update(uuid) { it.withCoordinateHud(value) }

    open fun setAll(
        uuid: UUID,
        mode: PhantomMode?,
        mentionPings: Boolean,
        acceptMessages: Boolean,
        locatorBar: Boolean,
        bingoMessages: Boolean,
        coordinateHud: Boolean,
    ) =
        update(uuid) {
            PlayerSettings(mode, mentionPings, acceptMessages, locatorBar, bingoMessages, coordinateHud)
        }

    /** Atomic read-modify-write preserves fields loaded concurrently with a local change. */
    private fun update(uuid: UUID, change: (PlayerSettings) -> PlayerSettings) {
        val updated = cache.compute(uuid) { _, current -> change(current ?: PlayerSettings.DEFAULTS) }!!
        notifySettingsChanged(uuid, updated)
        persist(uuid, updated)
    }

    @EventHandler
    open fun onPlayerJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { refreshOne(uuid) })
    }

    @EventHandler
    open fun onPlayerQuit(event: PlayerQuitEvent) {
        cache.remove(event.player.uniqueId)
    }

    private fun primeOnlinePlayers() {
        val capture = Runnable {
            val online = Bukkit.getOnlinePlayers().map { it.uniqueId }
            if (online.isNotEmpty()) {
                Bukkit.getScheduler()
                    .runTaskAsynchronously(
                        plugin,
                        Runnable {
                            for (uuid in online) refreshOne(uuid)
                        },
                    )
            }
        }
        if (Bukkit.isPrimaryThread()) capture.run() else Bukkit.getScheduler().runTask(plugin, capture)
    }

    private fun refreshOne(uuid: UUID) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) {
            if (cache.putIfAbsent(uuid, PlayerSettings.DEFAULTS) == null) {
                notifySettingsChanged(uuid, PlayerSettings.DEFAULTS)
            }
            return
        }
        var loaded = PlayerSettings.DEFAULTS
        try {
            pool.resource.use { jedis ->
                if (redisFailureLogged) {
                    plugin.getLogger().info("Player settings Redis connection recovered.")
                    redisFailureLogged = false
                }
                val json = jedis.hget(HASH_KEY, uuid.toString())
                if (json != null) loaded = PlayerSettings.fromJson(JsonParser.parseString(json).asJsonObject)
            }
        } catch (e: Exception) {
            if (!redisFailureLogged) {
                plugin.getLogger().warning("Player settings Redis unavailable; using defaults: ${e.message}")
                redisFailureLogged = true
            } else {
                plugin.getLogger().fine("Failed to load settings for $uuid: ${e.message}")
            }
        }
        // A local change during the Redis read wins over the stale loaded value.
        if (cache.putIfAbsent(uuid, loaded) == null) notifySettingsChanged(uuid, loaded)
    }

    private fun notifySettingsChanged(uuid: UUID, settings: PlayerSettings) {
        for (listener in listeners) {
            try {
                listener.onSettingsChanged(uuid, settings)
            } catch (e: Exception) {
                plugin.getLogger().fine("Settings listener failed for $uuid: ${e.message}")
            }
        }
    }

    private fun persist(uuid: UUID, settings: PlayerSettings) {
        if (jedisPool == null) return
        val envelope = settings.toJson().apply { addProperty("uuid", uuid.toString()) }
        val payload = envelope.toString()
        Bukkit.getScheduler()
            .runTaskAsynchronously(
                plugin,
                Runnable {
                    // Shutdown may close the pool between scheduling and running this worker.
                    val pool = jedisPool
                    if (pool == null || pool.isClosed) return@Runnable
                    try {
                        pool.resource.use { it.publish(SET_CHANNEL, payload) }
                    } catch (e: Exception) {
                        plugin.getLogger().warning("Failed to publish settings for $uuid: ${e.message}")
                    }
                },
            )
    }

    /** Persistent subscription with backoff and cache refresh after reconnecting. */
    private inner class SubscriberThread : Thread() {
        @Volatile var cancelled = false
        @Volatile var subscriber: JedisPubSub? = null

        override fun run() {
            var backoffMs = 1000L
            var warned = false
            while (!cancelled) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) return
                try {
                    pool.resource.use { jedis ->
                        if (warned) {
                            plugin.getLogger().info("Player settings Redis subscription reconnected.")
                            warned = false
                            primeOnlinePlayers()
                        }
                        val subscription =
                            object : JedisPubSub() {
                                override fun onMessage(channel: String, message: String) {
                                    if (channel == UPDATE_CHANNEL) ingest(message)
                                }
                            }
                        subscriber = subscription
                        backoffMs = 1000L
                        jedis.subscribe(subscription, UPDATE_CHANNEL)
                    }
                } catch (e: Exception) {
                    if (cancelled) return
                    if (!warned) {
                        plugin
                            .getLogger()
                            .warning("Player settings Redis subscription unavailable; retrying: ${e.message}")
                        warned = true
                    } else {
                        plugin.getLogger().fine("Settings subscription dropped: ${e.message}")
                    }
                    try {
                        sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                    backoffMs = minOf(30_000L, backoffMs * 2L)
                }
            }
        }
    }

    companion object {
        const val HASH_KEY = "crabutilities:settings"
        const val SET_CHANNEL = "crabutilities:settings-set"
        const val UPDATE_CHANNEL = "crabutilities:settings-updates"
    }
}
