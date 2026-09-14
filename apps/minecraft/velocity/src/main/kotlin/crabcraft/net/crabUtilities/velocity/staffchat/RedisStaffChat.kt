package crabcraft.net.crabUtilities.velocity.staffchat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.RedisPools
import crabcraft.net.crabUtilities.velocity.VelocityConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub

open class RedisStaffChat(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig) {
    private var jedisPool: JedisPool? = null
    private var subscriberThread: Thread? = null
    private var pubSub: JedisPubSub? = null
    open fun start() {
        jedisPool = RedisPools.create(config, 4)
        plugin.getLogger().info("Staff chat Redis subscriber starting for {}:{}; reconnects will run asynchronously.", config.getRedisHost(), config.getRedisPort())
        pubSub = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) {
                val staffMessage = decode(message) ?: return
                plugin.getServer().scheduler.buildTask(plugin, Runnable {
                    plugin.getStaffChatManager().displayMessage(staffMessage.senderName, staffMessage.message)
                }).schedule()
            }
        }
        subscriberThread = Thread({
            var warned = false
            while (!Thread.currentThread().isInterrupted) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) break
                try {
                    pool.resource.use { jedis ->
                        if (warned) { plugin.getLogger().info("Staff chat Redis subscriber reconnected."); warned = false }
                        jedis.subscribe(pubSub, config.getRedisChannel())
                    }
                } catch (_: NoClassDefFoundError) { break } // Classloader closed during shutdown.
                catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!warned) { plugin.getLogger().warn("Staff chat Redis subscriber unavailable, reconnecting in 3s...", e); warned = true }
                    else plugin.getLogger().debug("Staff chat Redis subscriber disconnected: {}", e.message)
                    try { Thread.sleep(3000) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
        }, "CrabUtilities-Redis-Subscriber")
        subscriberThread!!.isDaemon = true
        subscriberThread!!.start()
    }
    open fun publish(senderName: String, message: Component) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        val envelope = JsonObject()
        envelope.addProperty("sender", senderName)
        envelope.addProperty("message", GSON.serialize(message))
        val payload = envelope.toString()
        plugin.getServer().scheduler.buildTask(plugin, Runnable {
            try { pool.resource.use { jedis -> jedis.publish(config.getRedisChannel(), payload) } }
            catch (e: Exception) { plugin.getLogger().error("Failed to publish staff chat message to Redis", e) }
        }).schedule()
    }
    private fun decode(payload: String): StaffMessage? {
        try {
            val envelope = JsonParser.parseString(payload).asJsonObject
            val senderName = envelope.get("sender").asString
            val message = GSON.deserialize(envelope.get("message").asString)
            return StaffMessage(senderName, message)
        } catch (_: Exception) {
            // Accept the old delimiter format during a rolling proxy update.
            val separator = payload.indexOf(SEPARATOR)
            if (separator == -1) return null
            return StaffMessage(payload.substring(0, separator), Component.text(payload.substring(separator + 1)))
        }
    }
    open fun shutdown() {
        pubSub?.let { try { it.unsubscribe() } catch (_: Exception) {} }
        subscriberThread?.interrupt()
        val pool = jedisPool
        if (pool != null && !pool.isClosed) {
            try { pool.close() }
            catch (_: NoClassDefFoundError) { /* Relocated classes may be unavailable during shutdown. */ }
        }
    }
    private data class StaffMessage(val senderName: String, val message: Component)
    companion object {
        private const val SEPARATOR = "\u0000"
        private val GSON = GsonComponentSerializer.gson()
    }
}
