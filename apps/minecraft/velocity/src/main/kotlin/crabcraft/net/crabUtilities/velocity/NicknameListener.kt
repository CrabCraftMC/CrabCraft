package crabcraft.net.crabUtilities.velocity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub

import java.util.UUID

open class NicknameListener(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig) {
    private var jedisPool: JedisPool? = null
    private var subscriberThread: Thread? = null
    private var pubSub: JedisPubSub? = null
    @Volatile private var redisFailureLogged = false
    open fun start() {
        jedisPool = RedisPools.create(config, 2)
        pubSub = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) { if (channel == UPDATE_CHANNEL) ingest(message) }
        }
        subscriberThread = Thread({
            var warned = false
            while (!Thread.currentThread().isInterrupted) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) break
                try {
                    pool.resource.use { jedis ->
                        if (warned) { plugin.getLogger().info("Nickname Redis subscriber reconnected."); warned = false }
                        jedis.subscribe(pubSub, UPDATE_CHANNEL)
                    }
                } catch (_: NoClassDefFoundError) { break }
                catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!warned) { plugin.getLogger().warn("Nickname Redis subscriber unavailable, reconnecting in 3s...", e); warned = true }
                    else plugin.getLogger().debug("Nickname Redis subscriber disconnected: {}", e.message)
                    try { Thread.sleep(3000) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
        }, "CrabUtilities-Nickname-Subscriber")
        subscriberThread!!.isDaemon = true
        subscriberThread!!.start()
    }
    open fun publishNickname(uuid: UUID, raw: String?, expectedVersion: Long) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        val nickname = raw ?: ""
        plugin.getServer().scheduler.buildTask(plugin, Runnable {
            if (!plugin.getNicknameCache().isVersion(uuid, expectedVersion)) return@Runnable
            try {
                pool.resource.use { jedis ->
                    val actual = jedis.eval(PUBLISH_IF_ABSENT_SCRIPT, listOf(HASH_KEY), listOf(uuid.toString(), nickname, UPDATE_CHANNEL)) as String
                    if (reconcilePublishedNickname(plugin.getNicknameCache(), uuid, expectedVersion, nickname, actual)) persist(uuid)
                    if (redisFailureLogged) { plugin.getLogger().info("Nickname Redis publisher recovered."); redisFailureLogged = false }
                }
            } catch (e: Exception) {
                if (!redisFailureLogged) { plugin.getLogger().warn("Failed to publish nickname for {}; will retry on later updates", uuid, e); redisFailureLogged = true }
                else plugin.getLogger().debug("Failed to publish nickname for {}: {}", uuid, e.message)
            }
        }).schedule()
    }
    open fun loadRawNickname(uuid: UUID): String? {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return null
        try {
            pool.resource.use { jedis ->
                val raw = jedis.hget(HASH_KEY, uuid.toString())
                if (redisFailureLogged) { plugin.getLogger().info("Nickname Redis reader recovered."); redisFailureLogged = false }
                return raw
            }
        } catch (e: Exception) {
            if (!redisFailureLogged) { plugin.getLogger().warn("Failed to read nickname for {} from Redis", uuid, e); redisFailureLogged = true }
            else plugin.getLogger().debug("Failed to read nickname for {} from Redis: {}", uuid, e.message)
            return null
        }
    }
    private fun ingest(json: String) {
        val uuid: UUID
        val raw: String
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            uuid = UUID.fromString(obj.get("uuid").asString)
            raw = if (obj.has("raw") && !obj.get("raw").isJsonNull) obj.get("raw").asString else ""
        } catch (e: Exception) { plugin.getLogger().warn("Ignoring malformed nickname Redis update", e); return }
        if (plugin.getServer().getPlayer(uuid).filter { player -> player.isActive }.isEmpty) return
        plugin.getNicknameCache().setNickname(uuid, raw)
        plugin.getPendingJoinManager().complete(uuid)
        persist(uuid)
    }
    @Subscribe open fun onDisconnect(event: DisconnectEvent) {
        val uuid = event.player.uniqueId
        plugin.getPendingJoinManager().remove(uuid)
        plugin.getNicknameCache().remove(uuid)
        plugin.getMessageManager().clearReplyTargets(uuid)
        plugin.getMessageManager().clearSpy(uuid)
    }
    open fun shutdown() {
        pubSub?.let { try { it.unsubscribe() } catch (_: Exception) {} }
        subscriberThread?.let {
            it.interrupt()
            try { it.join(2000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val pool = jedisPool
        if (pool != null && !pool.isClosed) { try { pool.close() } catch (_: NoClassDefFoundError) {} }
        jedisPool = null
    }
    fun persist(uuid: UUID) {
        val uuidStr = uuid.toString()
        val plain = plugin.getNicknameCache().getPlainNickname(uuid)
        val raw = plugin.getNicknameCache().getRawNickname(uuid)
        plugin.runDatabaseTask("nickname-persist", Runnable { plugin.getPgWriter()!!.updateNickname(uuidStr, plain, raw) })
    }
    companion object {
        const val HASH_KEY = "crabutilities:nicknames"
        const val UPDATE_CHANNEL = "crabutilities:nicknames-updates"
        @JvmStatic fun reconcilePublishedNickname(cache: NicknameCache, uuid: UUID, expectedVersion: Long, proposed: String, actual: String?): Boolean =
            proposed != actual && cache.commitIfVersion(uuid, expectedVersion, actual)
        private val PUBLISH_IF_ABSENT_SCRIPT = """local current = redis.call('HGET', KEYS[1], ARGV[1])
if current == false then
    current = ARGV[2]
    redis.call('HSET', KEYS[1], ARGV[1], current)
end
local payload = cjson.encode({uuid = ARGV[1], raw = current})
redis.call('PUBLISH', ARGV[3], payload)
return current
"""
    }
}
