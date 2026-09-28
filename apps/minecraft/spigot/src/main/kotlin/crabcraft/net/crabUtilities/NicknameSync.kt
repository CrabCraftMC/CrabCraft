package crabcraft.net.crabUtilities

import com.earth2me.essentials.Essentials
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import net.ess3.api.events.AfkStatusChangeEvent
import net.ess3.api.events.NickChangeEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.PatternReplacementResult
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub

open class NicknameSync(private val plugin: CrabUtilities) : Listener {
    private var jedisPool: JedisPool? = null
    private var subscriberThread: SubscriberThread? = null
    @Volatile private var redisFailureLogged = false

    open fun start() {
        val redisHost = plugin.config.getString("redis.host", "localhost")
        val redisPort = plugin.config.getInt("redis.port", 6379)
        val redisPassword = plugin.config.getString("redis.password", "")
        val poolConfig = JedisPoolConfig().apply { maxTotal = 2 }
        jedisPool =
            if (!redisPassword.isNullOrEmpty()) JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword)
            else JedisPool(poolConfig, redisHost, redisPort, 2000)
        syncAll()
        val thread = SubscriberThread()
        subscriberThread = thread
        thread.name = "crabutilities-nickname-subscriber"
        thread.isDaemon = true
        thread.start()
        plugin.logger.info("Nickname Redis sync started; Redis will be retried asynchronously if unavailable.")
    }

    open fun shutdown() {
        subscriberThread?.let { thread ->
            thread.cancelled = true
            try {
                thread.subscriber?.takeIf { it.isSubscribed }?.unsubscribe()
            } catch (_: Exception) {}
            thread.interrupt()
        }
        subscriberThread = null
        jedisPool
            ?.takeUnless { it.isClosed }
            ?.let {
                try {
                    it.close()
                } catch (_: NoClassDefFoundError) {}
                jedisPool = null
            }
    }

    open fun syncAll() {
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                for (online in plugin.server.onlinePlayers) refreshOne(online.uniqueId)
            },
            20L,
        )
    }

    @EventHandler
    open fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Wait until EssentialsX has loaded the user before applying Redis state.
        plugin.server.scheduler.runTaskLater(plugin, Runnable { refreshOne(player.uniqueId) }, 20L)
    }

    /** Publish /nick changes to Velocity for immediate cache and database persistence. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onNickChange(event: NickChangeEvent) {
        val player = event.affected.base
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                val newNick = event.value ?: ""
                val essentials = plugin.getEssentials()
                if (player.isOnline && essentials is Essentials)
                    refreshComponentDisplayNick(essentials, player, newNick)
                publishNickname(player.uniqueId, newNick)
            },
            1L,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onAfkChange(event: AfkStatusChangeEvent) {
        val player = event.affected.base
        val essentials = plugin.getEssentials() as? Essentials ?: return
        val nickname = essentials.getUser(player)?.nickname ?: return
        // Essentials broadcasts immediately, then sets the display nick again.
        refreshComponentDisplayNick(essentials, player, nickname)
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (!player.isOnline) return@Runnable
                val currentNickname = essentials.getUser(player)?.nickname ?: return@Runnable
                refreshComponentDisplayNick(essentials, player, currentNickname)
            },
            1L,
        )
    }

    private fun refreshOne(uuid: UUID) {
        val pool = jedisPool ?: return
        if (pool.isClosed) return
        Bukkit.getScheduler()
            .runTaskAsynchronously(
                plugin,
                Runnable {
                    try {
                        pool.resource.use { jedis ->
                            val raw = jedis.hget(HASH_KEY, uuid.toString())
                            if (hasAuthoritativeRedisValue(raw)) applyNickname(uuid, raw)
                            if (redisFailureLogged) {
                                plugin.logger.info("Nickname Redis connection recovered.")
                                redisFailureLogged = false
                            }
                        }
                    } catch (e: Exception) {
                        if (!redisFailureLogged) {
                            plugin.logger.warning(
                                "Nickname Redis unavailable; will retry from subscription: ${e.message}"
                            )
                            redisFailureLogged = true
                        }
                    }
                },
            )
    }

    private fun applyNickname(uuid: UUID, authoritative: String?) {
        val task = Runnable {
            val target = plugin.server.getPlayer(uuid) ?: return@Runnable
            if (!target.isOnline) return@Runnable
            val essentials = plugin.getEssentials() as? Essentials ?: return@Runnable
            val user = essentials.getUser(target) ?: return@Runnable
            val localNick = user.nickname ?: ""
            val raw = authoritative ?: ""
            if (raw.isNotEmpty() && raw != localNick) {
                user.setNickname(raw)
                plugin.refreshMentionAutocomplete()
                plugin.logger.info("Synced nickname for ${target.name}: $raw")
            } else if (raw.isEmpty() && localNick.isNotEmpty()) {
                user.setNickname(null)
                plugin.refreshMentionAutocomplete()
                plugin.logger.info("Cleared nickname for ${target.name}")
            }
            user.setDisplayNick()
            refreshComponentDisplayNick(essentials, target, raw)
        }
        if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
    }

    private fun publishNickname(uuid: UUID, raw: String?) {
        val pool = jedisPool ?: return
        if (pool.isClosed) return
        val nickname = raw ?: ""
        val payload =
            JsonObject().apply {
                addProperty("uuid", uuid.toString())
                addProperty("raw", nickname)
            }
        Bukkit.getScheduler()
            .runTaskAsynchronously(
                plugin,
                Runnable {
                    try {
                        pool.resource.use { jedis ->
                            jedis.hset(HASH_KEY, uuid.toString(), nickname)
                            jedis.publish(UPDATE_CHANNEL, payload.toString())
                            if (redisFailureLogged) {
                                plugin.logger.info("Nickname Redis publisher recovered.")
                                redisFailureLogged = false
                            }
                        }
                    } catch (e: Exception) {
                        if (!redisFailureLogged) {
                            plugin.logger.warning(
                                "Failed to publish nickname update; will retry on the next change: ${e.message}"
                            )
                            redisFailureLogged = true
                        }
                    }
                },
            )
    }

    private fun ingest(json: String) {
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            val uuid = UUID.fromString(obj.get("uuid").asString)
            val raw = if (obj.has("raw") && !obj.get("raw").isJsonNull) obj.get("raw").asString else ""
            applyNickname(uuid, raw)
        } catch (e: Exception) {
            plugin.logger.fine("Bad nickname update payload: ${e.message}")
        }
    }

    private inner class SubscriberThread : Thread() {
        @Volatile var cancelled = false
        @Volatile var subscriber: JedisPubSub? = null

        override fun run() {
            var backoffMs = 1000L
            var warned = false
            while (!cancelled) {
                val pool = jedisPool ?: return
                if (pool.isClosed) return
                try {
                    pool.resource.use { jedis ->
                        if (warned) {
                            plugin.logger.info("Nickname Redis subscription reconnected.")
                            warned = false
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
                        plugin.logger.warning("Nickname Redis subscription unavailable; retrying: ${e.message}")
                        warned = true
                    } else plugin.logger.fine("Nickname subscription dropped: ${e.message}")
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                    backoffMs = minOf(30_000L, backoffMs * 2L)
                }
            }
        }
    }

    companion object {
        private const val HASH_KEY = "crabutilities:nicknames"
        private const val UPDATE_CHANNEL = "crabutilities:nicknames-updates"

        @JvmStatic fun hasAuthoritativeRedisValue(stored: String?): Boolean = stored != null

        @JvmStatic
        fun decoratedNickname(decorated: Component, raw: String?): Component {
            val nickname = NicknameComponentResolver.fromRawNick(raw) ?: return decorated
            replaceLastMatch(decorated, Pattern.compile(Pattern.quote(raw)), nickname)?.let {
                return it
            }
            val visibleNickname = PlainTextComponentSerializer.plainText().serialize(nickname)
            if (visibleNickname.isEmpty()) return decorated
            val visibleName =
                Pattern.compile(
                    "(?<![\\p{L}\\p{M}\\p{N}_-])" + Pattern.quote(visibleNickname) + "(?![\\p{L}\\p{M}\\p{N}_-])"
                )
            return replaceLastMatch(decorated, visibleName, nickname) ?: decorated
        }

        private fun replaceLastMatch(source: Component, pattern: Pattern, replacement: Component): Component? {
            val matchCount = AtomicInteger()
            source.replaceText { config ->
                config
                    .match(pattern)
                    .condition { _, seen, _ ->
                        matchCount.set(seen)
                        PatternReplacementResult.CONTINUE
                    }
                    .replacement(replacement)
            }
            val lastMatch = matchCount.get()
            if (lastMatch == 0) return null
            return source.replaceText { config ->
                config
                    .match(pattern)
                    .condition { _, seen, _ ->
                        if (seen == lastMatch) PatternReplacementResult.REPLACE else PatternReplacementResult.CONTINUE
                    }
                    .replacement(replacement)
            }
        }

        private fun refreshComponentDisplayNick(essentials: Essentials, player: Player, raw: String) {
            if (raw.isEmpty()) return
            if (essentials.settings.changeDisplayName())
                player.displayName(decoratedNickname(player.displayName(), raw))
            val playerListName = player.playerListName()
            if (playerListName != null && essentials.settings.changePlayerListName())
                player.playerListName(decoratedNickname(playerListName, raw))
        }
    }
}
