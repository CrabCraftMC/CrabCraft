package crabcraft.net.crabUtilities.velocity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.velocity.db.PlayerSettingsRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub

/**
 * Proxy-owned per-player settings. Postgres is authoritative; Redis carries the join cache and backend change requests.
 * Unknown settings fields flow through untouched.
 */
open class PlayerSettingsService(
    private val plugin: CrabUtilitiesVelocity,
    private val repository: PlayerSettingsRepository,
    private val config: VelocityConfig,
) {
    private val cache = ConcurrentHashMap<UUID, JsonObject>()
    @Volatile private var jedisPool: JedisPool? = null
    @Volatile private var pubSub: JedisPubSub? = null
    private var subscriberThread: Thread? = null
    @Volatile private var stopped = false

    open fun start() {
        // The persistent subscriber holds one connection; leave room for login seeds and broadcasts.
        jedisPool = RedisPools.create(config, 4)
        startSubscriber()
        plugin.getLogger().info("Player settings service started (Postgres-backed; Redis cache/transport).")
    }

    /** Loads a player's settings from Postgres on login and seeds the cache and Redis. */
    open fun onLogin(uuid: UUID) {
        plugin.runDatabaseTask("settings-load") {
            val settings = parseOrEmpty(repository.load(uuid.toString()))
            cache[uuid] = settings
            // Broadcast as well as seeding so a backend that already queried the hash still converges.
            persistToRedis(uuid, settings)
        }
    }

    open fun onDisconnect(uuid: UUID) {
        cache.remove(uuid)
    }

    /** Proxy-side private-message permission, enabled by default. */
    open fun acceptsMessages(uuid: UUID): Boolean = readBool(cache[uuid], "acceptMessages", true)

    open fun shutdown() {
        stopped = true
        pubSub?.let {
            try {
                it.unsubscribe()
            } catch (ignored: Exception) {}
        }
        subscriberThread?.interrupt()
        subscriberThread = null
        jedisPool?.let {
            if (!it.isClosed) {
                try {
                    it.close()
                } catch (ignored: NoClassDefFoundError) {}
                jedisPool = null
            }
        }
        cache.clear()
    }

    private fun parseOrEmpty(json: String?): JsonObject {
        if (json == null) return JsonObject()
        return try {
            JsonParser.parseString(json).asJsonObject
        } catch (error: Exception) {
            JsonObject()
        }
    }

    private fun readBool(settings: JsonObject?, key: String, fallback: Boolean): Boolean {
        if (settings == null || !settings.has(key)) return fallback
        return try {
            settings.get(key).asBoolean
        } catch (error: Exception) {
            fallback
        }
    }

    /** Updates the join-time hash and publishes an envelope carrying the player's UUID. */
    private fun persistToRedis(uuid: UUID, settings: JsonObject) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        try {
            pool.resource.use { jedis ->
                jedis.hset(HASH_KEY, uuid.toString(), settings.toString())
                val envelope = settings.deepCopy()
                envelope.addProperty("uuid", uuid.toString())
                jedis.publish(UPDATE_CHANNEL, envelope.toString())
            }
        } catch (error: Exception) {
            plugin.getLogger().debug("Failed to write settings to Redis for {}: {}", uuid, error.message)
        }
    }

    /** Persists backend change requests and re-broadcasts the authoritative settings. */
    private fun handleSetRequest(payload: String) {
        val settings =
            try {
                JsonParser.parseString(payload).asJsonObject
            } catch (error: Exception) {
                return
            }
        if (!settings.has("uuid")) return
        val uuid =
            try {
                UUID.fromString(settings.get("uuid").asString)
            } catch (error: Exception) {
                return
            }
        // Remove the transport-only UUID before storing the settings.
        settings.remove("uuid")
        val settingsJson = settings.toString()
        cache[uuid] = settings
        plugin.runDatabaseTask("settings-save") { repository.save(uuid.toString(), settingsJson) }
        persistToRedis(uuid, settings)
    }

    private fun startSubscriber() {
        subscriberThread =
            Thread(
                    {
                        var warned = false
                        while (!stopped && !Thread.currentThread().isInterrupted) {
                            val pool = jedisPool
                            if (pool == null || pool.isClosed) break
                            try {
                                pool.resource.use { jedis ->
                                    if (warned) {
                                        plugin.getLogger().info("Settings Redis subscriber reconnected.")
                                        warned = false
                                    }
                                    pubSub =
                                        object : JedisPubSub() {
                                            override fun onMessage(channel: String, message: String) {
                                                if (SET_CHANNEL == channel) {
                                                    try {
                                                        handleSetRequest(message)
                                                    } catch (error: Throwable) {
                                                        plugin
                                                            .getLogger()
                                                            .debug("Settings set handler threw: {}", error.message)
                                                    }
                                                }
                                            }
                                        }
                                    jedis.subscribe(pubSub, SET_CHANNEL)
                                }
                            } catch (error: NoClassDefFoundError) {
                                break
                            } catch (error: Exception) {
                                if (Thread.currentThread().isInterrupted) break
                                if (!warned) {
                                    plugin
                                        .getLogger()
                                        .warn(
                                            "Settings Redis subscriber unavailable; reconnecting in 3s: {}",
                                            error.message,
                                        )
                                    warned = true
                                }
                                try {
                                    Thread.sleep(3000L)
                                } catch (interrupted: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    break
                                }
                            }
                        }
                    },
                    "CrabUtilities-Settings",
                )
                .apply {
                    isDaemon = true
                    start()
                }
    }

    companion object {
        const val HASH_KEY = "crabutilities:settings"
        const val SET_CHANNEL = "crabutilities:settings-set"
        const val UPDATE_CHANNEL = "crabutilities:settings-updates"
    }
}
