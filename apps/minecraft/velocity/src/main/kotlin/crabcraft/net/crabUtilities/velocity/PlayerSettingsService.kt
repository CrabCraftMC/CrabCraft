package crabcraft.net.crabUtilities.velocity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.velocity.db.PlayerSettingsRepository
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Proxy-owned settings: Postgres is authoritative and Redis caches and transports the raw JSON. */
open class PlayerSettingsService(private val plugin: CrabUtilitiesVelocity, private val repository: PlayerSettingsRepository,
                                 private val config: VelocityConfig) {
    private val cache = ConcurrentHashMap<UUID, JsonObject>()
    @Volatile private var jedisPool: JedisPool? = null
    @Volatile private var pubSub: JedisPubSub? = null
    private var subscriberThread: Thread? = null
    @Volatile private var stopped = false
    open fun start() {
        // Leave headroom beside the persistent subscriber for seeds and broadcasts.
        jedisPool = RedisPools.create(config, 4)
        startSubscriber()
        plugin.getLogger().info("Player settings service started (Postgres-backed; Redis cache/transport).")
    }
    /** Loads settings from Postgres on login and seeds the cache and Redis. */
    open fun onLogin(uuid: UUID) {
        plugin.runDatabaseTask("settings-load", Runnable {
            val settings = parseOrEmpty(repository.load(uuid.toString()))
            cache[uuid] = settings
            // Broadcast too so a backend whose join HGET races this load still converges.
            persistToRedis(uuid, settings, true)
        })
    }
    open fun onDisconnect(uuid: UUID) { cache.remove(uuid) }
    open fun acceptsMessages(uuid: UUID): Boolean = readBool(cache[uuid], "acceptMessages", true)
    open fun shutdown() {
        stopped = true
        pubSub?.let { try { it.unsubscribe() } catch (_: Exception) {} }
        subscriberThread?.interrupt(); subscriberThread = null
        val pool = jedisPool
        if (pool != null && !pool.isClosed) {
            try { pool.close() } catch (_: NoClassDefFoundError) {}
            jedisPool = null
        }
        cache.clear()
    }
    /** Writes the join-time Redis hash and optionally broadcasts a UUID envelope. */
    private fun persistToRedis(uuid: UUID, settings: JsonObject, broadcast: Boolean) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        try {
            pool.resource.use { jedis ->
                jedis.hset(HASH_KEY, uuid.toString(), settings.toString())
                if (broadcast) {
                    val envelope = settings.deepCopy()
                    envelope.addProperty("uuid", uuid.toString())
                    jedis.publish(UPDATE_CHANNEL, envelope.toString())
                }
            }
        } catch (e: Exception) { plugin.getLogger().debug("Failed to write settings to Redis for {}: {}", uuid, e.message) }
    }
    /** Persists a backend change request and broadcasts the authoritative state. */
    private fun handleSetRequest(payload: String) {
        val envelope = try { JsonParser.parseString(payload).asJsonObject } catch (_: Exception) { return }
        if (!envelope.has("uuid")) return
        val uuid = try { UUID.fromString(envelope.get("uuid").asString) } catch (_: Exception) { return }
        // Remove the transport-only UUID from the settings object.
        val settings = envelope.deepCopy()
        settings.remove("uuid")
        val settingsJson = settings.toString()
        cache[uuid] = settings
        plugin.runDatabaseTask("settings-save", Runnable { repository.save(uuid.toString(), settingsJson) })
        persistToRedis(uuid, settings, true)
    }
    private fun startSubscriber() {
        subscriberThread = Thread({
            var warned = false
            while (!stopped && !Thread.currentThread().isInterrupted) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) break
                try {
                    pool.resource.use { jedis ->
                        if (warned) { plugin.getLogger().info("Settings Redis subscriber reconnected."); warned = false }
                        pubSub = object : JedisPubSub() {
                            override fun onMessage(channel: String, message: String) {
                                if (channel == SET_CHANNEL) {
                                    try { handleSetRequest(message) }
                                    catch (t: Throwable) { plugin.getLogger().debug("Settings set handler threw: {}", t.message) }
                                }
                            }
                        }
                        jedis.subscribe(pubSub, SET_CHANNEL)
                    }
                } catch (_: NoClassDefFoundError) { break }
                catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!warned) {
                        plugin.getLogger().warn("Settings Redis subscriber unavailable; reconnecting in 3s: {}", e.message)
                        warned = true
                    }
                    try { Thread.sleep(3000L) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
        }, "CrabUtilities-Settings")
        subscriberThread!!.isDaemon = true
        subscriberThread!!.start()
    }
    companion object {
        const val HASH_KEY = "crabutilities:settings"
        const val SET_CHANNEL = "crabutilities:settings-set"
        const val UPDATE_CHANNEL = "crabutilities:settings-updates"
        private fun parseOrEmpty(json: String?): JsonObject {
            if (json == null) return JsonObject()
            return try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { JsonObject() }
        }
        private fun readBool(settings: JsonObject?, key: String, fallback: Boolean): Boolean {
            if (settings == null || !settings.has(key)) return fallback
            return try { settings.get(key).asBoolean } catch (_: Exception) { fallback }
        }
    }
}
