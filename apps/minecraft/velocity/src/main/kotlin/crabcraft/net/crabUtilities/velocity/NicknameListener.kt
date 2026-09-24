package crabcraft.net.crabUtilities.velocity

import com.google.gson.JsonParser
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import crabcraft.net.crabUtilities.redis.RedisSubscriberThread
import java.util.UUID
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub

open class NicknameListener(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig) {
    private var jedisPool: JedisPool? = null
    private var subscriberThread: Thread? = null
    private var pubSub: JedisPubSub? = null
    @Volatile private var redisFailureLogged = false

    open fun start() {
        jedisPool = RedisPools.create(config, 2)
        pubSub =
            object : JedisPubSub() {
                override fun onMessage(channel: String, message: String) {
                    if (UPDATE_CHANNEL == channel) ingest(message)
                }
            }
        subscriberThread =
            RedisSubscriberThread.start(
                "CrabUtilities-Nickname-Subscriber",
                { jedisPool },
                { jedis -> jedis.subscribe(pubSub, UPDATE_CHANNEL) },
                { plugin.getLogger().info("Nickname Redis subscriber reconnected.") },
                { error, firstFailure ->
                    if (firstFailure) {
                        plugin.getLogger().warn("Nickname Redis subscriber unavailable, reconnecting in 3s...", error)
                    } else plugin.getLogger().debug("Nickname Redis subscriber disconnected: {}", error.message)
                },
            )
    }

    open fun publishNickname(uuid: UUID, raw: String?, expectedVersion: Long) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        val nickname = raw ?: ""
        plugin
            .getServer()
            .scheduler
            .buildTask(
                plugin,
                Runnable {
                    if (!plugin.getNicknameCache().isVersion(uuid, expectedVersion)) return@Runnable
                    try {
                        pool.resource.use { jedis ->
                            val actual =
                                jedis.eval(
                                    PUBLISH_IF_ABSENT_SCRIPT,
                                    listOf(HASH_KEY),
                                    listOf(uuid.toString(), nickname, UPDATE_CHANNEL),
                                ) as String?
                            if (
                                reconcilePublishedNickname(
                                    plugin.getNicknameCache(),
                                    uuid,
                                    expectedVersion,
                                    nickname,
                                    actual,
                                )
                            ) {
                                persist(uuid)
                            }
                            if (redisFailureLogged) {
                                plugin.getLogger().info("Nickname Redis publisher recovered.")
                                redisFailureLogged = false
                            }
                        }
                    } catch (error: Exception) {
                        if (!redisFailureLogged) {
                            plugin
                                .getLogger()
                                .warn("Failed to publish nickname for {}; will retry on later updates", uuid, error)
                            redisFailureLogged = true
                        } else plugin.getLogger().debug("Failed to publish nickname for {}: {}", uuid, error.message)
                    }
                },
            )
            .schedule()
    }

    open fun loadRawNickname(uuid: UUID): String? {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return null
        return try {
            pool.resource.use { jedis ->
                val raw = jedis.hget(HASH_KEY, uuid.toString())
                if (redisFailureLogged) {
                    plugin.getLogger().info("Nickname Redis reader recovered.")
                    redisFailureLogged = false
                }
                raw
            }
        } catch (error: Exception) {
            if (!redisFailureLogged) {
                plugin.getLogger().warn("Failed to read nickname for {} from Redis", uuid, error)
                redisFailureLogged = true
            } else plugin.getLogger().debug("Failed to read nickname for {} from Redis: {}", uuid, error.message)
            null
        }
    }

    private fun ingest(json: String) {
        val uuid: UUID
        val raw: String
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            uuid = UUID.fromString(obj.get("uuid").asString)
            raw = if (obj.has("raw") && !obj.get("raw").isJsonNull) obj.get("raw").asString else ""
        } catch (error: Exception) {
            plugin.getLogger().warn("Ignoring malformed nickname Redis update", error)
            return
        }
        if (plugin.getServer().getPlayer(uuid).filter { it.isActive }.isEmpty) return
        plugin.getNicknameCache().setNickname(uuid, raw)
        plugin.getPendingJoinManager().complete(uuid)
        persist(uuid)
    }

    @Subscribe
    open fun onDisconnect(event: DisconnectEvent) {
        val uuid = event.player.uniqueId
        plugin.getPendingJoinManager().remove(uuid)
        plugin.getNicknameCache().remove(uuid)
        plugin.getMessageManager()?.let {
            it.clearReplyTargets(uuid)
            it.clearSpy(uuid)
        }
    }

    open fun shutdown() {
        pubSub?.let {
            try {
                it.unsubscribe()
            } catch (ignored: Exception) {}
        }
        subscriberThread?.let {
            it.interrupt()
            try {
                it.join(2000)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        jedisPool?.let {
            if (!it.isClosed)
                try {
                    it.close()
                } catch (ignored: NoClassDefFoundError) {}
        }
        jedisPool = null
    }

    fun persist(uuid: UUID) {
        val uuidString = uuid.toString()
        val plain = plugin.getNicknameCache().getPlainNickname(uuid)
        val raw = plugin.getNicknameCache().getRawNickname(uuid)
        plugin.runDatabaseTask("nickname-persist") { plugin.getPgWriter()!!.updateNickname(uuidString, plain, raw) }
    }

    companion object {
        const val HASH_KEY = "crabutilities:nicknames"
        const val UPDATE_CHANNEL = "crabutilities:nicknames-updates"
        private val PUBLISH_IF_ABSENT_SCRIPT =
            """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current == false then
                current = ARGV[2]
                redis.call('HSET', KEYS[1], ARGV[1], current)
            end
            local payload = cjson.encode({uuid = ARGV[1], raw = current})
            redis.call('PUBLISH', ARGV[3], payload)
            return current
            """
                .trimIndent() + "\n"

        @JvmStatic
        fun reconcilePublishedNickname(
            cache: NicknameCache,
            uuid: UUID,
            expectedVersion: Long,
            proposed: String,
            actual: String?,
        ): Boolean = proposed != actual && cache.commitIfVersion(uuid, expectedVersion, actual)
    }
}
