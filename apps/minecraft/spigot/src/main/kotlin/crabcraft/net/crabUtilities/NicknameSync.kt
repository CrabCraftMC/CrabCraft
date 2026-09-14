package crabcraft.net.crabUtilities

import com.earth2me.essentials.Essentials
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

open class NicknameSync(private val plugin: CrabUtilities) : Listener {
    private var jedisPool: JedisPool? = null
    private var subscriberThread: SubscriberThread? = null
    @Volatile private var redisFailureLogged = false

    open fun start() {
        val redisHost = plugin.getConfig().getString("redis.host", "localhost")
        val redisPort = plugin.getConfig().getInt("redis.port", 6379)
        val redisPassword = plugin.getConfig().getString("redis.password", "")
        val poolConfig = JedisPoolConfig()
        poolConfig.setMaxTotal(2)
        jedisPool = if (!redisPassword.isNullOrEmpty()) {
            JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword)
        } else {
            JedisPool(poolConfig, redisHost, redisPort, 2000)
        }
        syncAll()
        subscriberThread = SubscriberThread().also {
            it.name = "crabutilities-nickname-subscriber"
            it.isDaemon = true
            it.start()
        }
        plugin.getLogger().info("Nickname Redis sync started; Redis will be retried asynchronously if unavailable.")
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

    open fun syncAll() {
        plugin.getServer().getScheduler().runTaskLater(plugin, Runnable {
            for (online in plugin.getServer().getOnlinePlayers()) refreshOne(online.getUniqueId())
        }, 20L)
    }

    @EventHandler
    open fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.getPlayer()
        // Delay so EssentialsX has loaded the user before applying the Redis mirror.
        plugin.getServer().getScheduler().runTaskLater(plugin, Runnable { refreshOne(player.getUniqueId()) }, 20L)
    }

    /** Push a changed nickname to Velocity for immediate caching and persistence. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onNickChange(event: NickChangeEvent) {
        val player = event.getAffected().getBase()
        // Delay one tick so EssentialsX has finished updating internally.
        plugin.getServer().getScheduler().runTaskLater(plugin, Runnable {
            val newNick = event.getValue() ?: ""
            val essentials = plugin.getEssentials()
            if (player.isOnline() && essentials is Essentials) {
                refreshComponentDisplayNick(essentials, player, newNick)
            }
            publishNickname(player.getUniqueId(), newNick)
        }, 1L)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onAfkChange(event: AfkStatusChangeEvent) {
        val player = event.getAffected().getBase()
        val essentials = plugin.getEssentials() as? Essentials ?: return
        val user = essentials.getUser(player) ?: return
        val nickname = user.getNickname() ?: return
        // Essentials broadcasts the AFK change immediately after this event.
        refreshComponentDisplayNick(essentials, player, nickname)
        // Essentials then calls setDisplayNick() again, so repair that final value too.
        plugin.getServer().getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline()) return@Runnable
            val currentUser = essentials.getUser(player) ?: return@Runnable
            val currentNickname = currentUser.getNickname() ?: return@Runnable
            refreshComponentDisplayNick(essentials, player, currentNickname)
        }, 1L)
    }

    private fun refreshOne(uuid: UUID) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                pool.resource.use { jedis ->
                    val raw = jedis.hget(HASH_KEY, uuid.toString())
                    if (hasAuthoritativeRedisValue(raw)) applyNickname(uuid, raw)
                    if (redisFailureLogged) {
                        plugin.getLogger().info("Nickname Redis connection recovered.")
                        redisFailureLogged = false
                    }
                }
            } catch (e: Exception) {
                if (!redisFailureLogged) {
                    plugin.getLogger().warning("Nickname Redis unavailable; will retry from subscription: " + e.message)
                    redisFailureLogged = true
                }
            }
        })
    }

    private fun applyNickname(uuid: UUID, authoritative: String?) {
        val task = Runnable {
            val target = plugin.getServer().getPlayer(uuid) ?: return@Runnable
            if (!target.isOnline()) return@Runnable
            val essentials = plugin.getEssentials() as? Essentials ?: return@Runnable
            val user = essentials.getUser(target) ?: return@Runnable
            val localNick = user.getNickname() ?: ""
            val raw = authoritative ?: ""
            if (raw.isNotEmpty() && raw != localNick) {
                user.setNickname(raw)
                plugin.refreshMentionAutocomplete()
                plugin.getLogger().info("Synced nickname for " + target.getName() + ": " + raw)
            } else if (raw.isEmpty() && localNick.isNotEmpty()) {
                user.setNickname(null)
                plugin.refreshMentionAutocomplete()
                plugin.getLogger().info("Cleared nickname for " + target.getName())
            }
            user.setDisplayNick()
            refreshComponentDisplayNick(essentials, target, raw)
        }
        if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
    }

    private fun publishNickname(uuid: UUID, raw: String?) {
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        val nickname = raw ?: ""
        val payload = JsonObject()
        payload.addProperty("uuid", uuid.toString())
        payload.addProperty("raw", nickname)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                pool.resource.use { jedis ->
                    jedis.hset(HASH_KEY, uuid.toString(), nickname)
                    jedis.publish(UPDATE_CHANNEL, payload.toString())
                    if (redisFailureLogged) {
                        plugin.getLogger().info("Nickname Redis publisher recovered.")
                        redisFailureLogged = false
                    }
                }
            } catch (e: Exception) {
                if (!redisFailureLogged) {
                    plugin.getLogger().warning("Failed to publish nickname update; will retry on the next change: " + e.message)
                    redisFailureLogged = true
                }
            }
        })
    }

    private fun ingest(json: String) {
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            val uuid = UUID.fromString(obj.get("uuid").asString)
            val raw = if (obj.has("raw") && !obj.get("raw").isJsonNull) obj.get("raw").asString else ""
            applyNickname(uuid, raw)
        } catch (e: Exception) {
            plugin.getLogger().fine("Bad nickname update payload: " + e.message)
        }
    }

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
                            plugin.getLogger().info("Nickname Redis subscription reconnected.")
                            warned = false
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
                        plugin.getLogger().warning("Nickname Redis subscription unavailable; retrying: " + e.message)
                        warned = true
                    } else {
                        plugin.getLogger().fine("Nickname subscription dropped: " + e.message)
                    }
                    try { sleep(backoffMs) } catch (ie: InterruptedException) { return }
                    backoffMs = minOf(30_000L, backoffMs * 2L)
                }
            }
        }
    }

    companion object {
        private const val HASH_KEY = "crabutilities:nicknames"
        private const val UPDATE_CHANNEL = "crabutilities:nicknames-updates"

        @JvmStatic
        fun hasAuthoritativeRedisValue(stored: String?): Boolean = stored != null

        @JvmStatic
        fun decoratedNickname(decorated: Component, raw: String?): Component {
            val nickname = NicknameComponentResolver.fromRawNick(raw) ?: return decorated
            val rawReplacement = replaceLastMatch(decorated, Pattern.compile(Pattern.quote(raw)), nickname)
            if (rawReplacement != null) return rawReplacement
            val visibleNickname = PlainTextComponentSerializer.plainText().serialize(nickname)
            if (visibleNickname.isEmpty()) return decorated
            val visibleName = Pattern.compile(
                "(?<![\\p{L}\\p{M}\\p{N}_-])" + Pattern.quote(visibleNickname) + "(?![\\p{L}\\p{M}\\p{N}_-])")
            return replaceLastMatch(decorated, visibleName, nickname) ?: decorated
        }

        private fun replaceLastMatch(source: Component, pattern: Pattern, replacement: Component): Component? {
            val matchCount = AtomicInteger()
            source.replaceText { config -> config.match(pattern)
                .condition { _, seen, _ ->
                    matchCount.set(seen)
                    PatternReplacementResult.CONTINUE
                }.replacement(replacement)
            }
            val lastMatch = matchCount.get()
            if (lastMatch == 0) return null
            return source.replaceText { config -> config.match(pattern)
                .condition { _, seen, _ ->
                    if (seen == lastMatch) PatternReplacementResult.REPLACE else PatternReplacementResult.CONTINUE
                }.replacement(replacement)
            }
        }

        private fun refreshComponentDisplayNick(essentials: Essentials, player: Player, raw: String) {
            if (raw.isEmpty()) return
            if (essentials.getSettings().changeDisplayName()) {
                player.displayName(decoratedNickname(player.displayName(), raw))
            }
            val playerListName: Component? = player.playerListName()
            if (playerListName != null && essentials.getSettings().changePlayerListName()) {
                player.playerListName(decoratedNickname(playerListName, raw))
            }
        }
    }
}
