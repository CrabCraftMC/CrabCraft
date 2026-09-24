package crabcraft.net.crabUtilities.velocity.staffchat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.redis.RedisSubscriberThread
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
        plugin
            .getLogger()
            .info(
                "Staff chat Redis subscriber starting for {}:{}; reconnects will run asynchronously.",
                config.getRedisHost(),
                config.getRedisPort(),
            )
        pubSub =
            object : JedisPubSub() {
                override fun onMessage(channel: String, message: String) {
                    val staffMessage = decode(message) ?: return
                    plugin
                        .getServer()
                        .scheduler
                        .buildTask(
                            plugin,
                            Runnable {
                                plugin
                                    .getStaffChatManager()!!
                                    .displayMessage(staffMessage.senderName, staffMessage.message)
                            },
                        )
                        .schedule()
                }
            }
        subscriberThread =
            RedisSubscriberThread.start(
                "CrabUtilities-Redis-Subscriber",
                { jedisPool },
                { jedis -> jedis.subscribe(pubSub, config.getRedisChannel()) },
                { plugin.getLogger().info("Staff chat Redis subscriber reconnected.") },
                { error, firstFailure ->
                    if (firstFailure) {
                        plugin.getLogger().warn("Staff chat Redis subscriber unavailable, reconnecting in 3s...", error)
                    } else {
                        plugin.getLogger().debug("Staff chat Redis subscriber disconnected: {}", error.message)
                    }
                },
            )
    }

    open fun publish(senderName: String, message: Component) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        val payload =
            JsonObject()
                .apply {
                    addProperty("sender", senderName)
                    addProperty("message", GSON.serialize(message))
                }
                .toString()
        plugin
            .getServer()
            .scheduler
            .buildTask(
                plugin,
                Runnable {
                    try {
                        pool.resource.use { it.publish(config.getRedisChannel(), payload) }
                    } catch (e: Exception) {
                        plugin.getLogger().error("Failed to publish staff chat message to Redis", e)
                    }
                },
            )
            .schedule()
    }

    private fun decode(payload: String): StaffMessage? =
        try {
            val envelope = JsonParser.parseString(payload).asJsonObject
            StaffMessage(envelope.get("sender").asString, GSON.deserialize(envelope.get("message").asString))
        } catch (_: Exception) {
            // Accept the delimiter format while proxies are updated individually.
            val separator = payload.indexOf(SEPARATOR)
            if (separator == -1) null
            else
                StaffMessage(
                    payload.substring(0, separator),
                    Component.text(payload.substring(separator + 1)),
                )
        }

    open fun shutdown() {
        try {
            pubSub?.unsubscribe()
        } catch (_: Exception) {}
        subscriberThread?.interrupt()
        jedisPool?.let { pool ->
            if (!pool.isClosed) {
                try {
                    pool.close()
                } catch (_: NoClassDefFoundError) {
                    // Velocity may restrict loading relocated classes during shutdown.
                }
            }
        }
    }

    private data class StaffMessage(val senderName: String, val message: Component)

    companion object {
        private const val SEPARATOR = "\u0000"
        private val GSON = GsonComponentSerializer.gson()
    }
}
