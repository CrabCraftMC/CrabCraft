package crabcraft.net.crabUtilities.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.NicknameComponentResolver
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.params.XAddParams
import java.net.URI
import java.util.UUID
import java.util.regex.Pattern

/**
 * Formats this backend's chat and bridges it to opted-in backends over Redis.
 * The subscriber reconnects after a three-second backoff; publishes are async.
 * Player text permits visual MiniMessage tags only. Delivery and pings run on
 * the main thread.
 */
open class GlobalChatService(private val plugin: CrabUtilities) {
    private val host = plugin.getConfig().getString("redis.host", "localhost")!!
    private val port = plugin.getConfig().getInt("redis.port", 6379)
    private val password = plugin.getConfig().getString("redis.password", "")
    private val enabled = plugin.getConfig().getBoolean("global-chat.enabled", false)
    private val gorkEnabled = plugin.getConfig().getBoolean("global-chat.gork.enabled", true)
    private val gorkManager = GorkManager()
    private val publicChatEnabled = plugin.getConfig().getBoolean("public-chat.enabled", false)
    private val publicChatStream = plugin.getConfig().getString("public-chat.stream", "crabcraft:public-chat")
        ?.takeUnless { text -> text.all { Character.isWhitespace(it) } } ?: "crabcraft:public-chat"
    private val publicChatMaxMessages = maxOf(1L, plugin.getConfig().getLong("public-chat.max-messages", 500L))
    private val format = plugin.getConfig().getString("global-chat.format", "<display_name><gray>:</gray> <message>")!!
    private val mentionProcessor: MentionProcessor
    private val soundEnabled = plugin.getConfig().getBoolean("global-chat.mentions.sound.enabled", true)
    private val mentionSound: Sound

    /** Identity of this process; used to drop our own echoed messages. */
    private val serverId = UUID.randomUUID()
    private val miniMessage = MiniMessage.miniMessage()
    private var jedisPool: JedisPool? = null
    private var pubSub: JedisPubSub? = null
    private var subscriberThread: Thread? = null
    @Volatile private var stopped = false

    init {
        val mentionsEnabled = plugin.getConfig().getBoolean("global-chat.mentions.enabled", true)
        val prefix = plugin.getConfig().getString("global-chat.mentions.prefix", "@")!!
        val highlight = plugin.getConfig().getString("global-chat.mentions.highlight", "<yellow><name></yellow>")!!
        mentionProcessor = MentionProcessor(mentionsEnabled, prefix, highlight, miniMessage, plugin.getEssentials())
        val soundKey = plugin.getConfig().getString("global-chat.mentions.sound.key", "minecraft:block.note_block.pling")!!
        val volume = plugin.getConfig().getDouble("global-chat.mentions.sound.volume", 1.0).toFloat()
        val pitch = plugin.getConfig().getDouble("global-chat.mentions.sound.pitch", 1.0).toFloat()
        mentionSound = Sound.sound(Key.key(soundKey), Sound.Source.MASTER, volume, pitch)
    }

    open fun start() {
        if (!enabled && !publicChatEnabled) {
            plugin.getLogger().info("Global and public chat are disabled for this server; chat stays local.")
            return
        }
        val poolConfig = JedisPoolConfig()
        poolConfig.setMaxTotal(4)
        jedisPool = if (!password.isNullOrEmpty()) JedisPool(poolConfig, host, port, 2000, password)
            else JedisPool(poolConfig, host, port, 2000)
        // Servers outside global chat still need the pool when their public
        // feed is enabled, but must not display the network's global chat.
        if (enabled) startSubscriber()
        plugin.getLogger().info("Chat Redis started (global=$enabled, public=$publicChatEnabled, serverId=$serverId); Redis will be retried asynchronously if unavailable.")
    }

    open fun isEnabled(): Boolean = enabled
    open fun isGorkEnabled(): Boolean = enabled && gorkEnabled

    private fun startSubscriber() {
        val subscriber = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) {
                try {
                    handleIncoming(message)
                } catch (t: Throwable) {
                    plugin.getLogger().fine("Global chat handler threw: " + t.message)
                }
            }
        }
        pubSub = subscriber
        val thread = Thread({
            var warned = false
            while (!stopped && !Thread.currentThread().isInterrupted) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) break
                try {
                    pool.resource.use { jedis ->
                        if (warned) {
                            plugin.getLogger().info("Global chat Redis subscriber reconnected.")
                            warned = false
                        }
                        jedis.subscribe(subscriber, CHANNEL)
                    }
                } catch (e: NoClassDefFoundError) {
                    break
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!warned) {
                        plugin.getLogger().warning("Global chat Redis subscriber unavailable; reconnecting in 3s: " + e.message)
                        warned = true
                    } else {
                        plugin.getLogger().fine("Global chat subscriber disconnected: " + e.message)
                    }
                    try {
                        Thread.sleep(3000L)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }, "CrabUtilities-GlobalChat")
        subscriberThread = thread
        thread.isDaemon = true
        thread.start()
    }

    /** Holds a rendered chat line and the local players it pinged. */
    data class RenderedLine(private val line: Component, private val mentioned: Set<UUID>) {
        fun line(): Component = line
        fun mentioned(): Set<UUID> = mentioned
    }

    /**
     * Renders one line with component placeholders for the display name and
     * parsed message, and an unparsed username. Must run on the main thread
     * because mention resolution reads the online player list and nicknames.
     */
    open fun renderLine(displayName: Component, username: String, rawMessage: String, senderUuid: UUID): RenderedLine {
        val styledMessage = SafeChatMiniMessage.deserialize(rawMessage)
        val mention = mentionProcessor.process(styledMessage, senderUuid)
        val clickableDisplayName = displayName.clickEvent(ClickEvent.suggestCommand(messageCommand(username)))
        val line = miniMessage.deserialize(format,
            Placeholder.component("display_name", clickableDisplayName),
            Placeholder.unparsed("username", username),
            Placeholder.component("message", linkifyUrls(mention.message())))
        return RenderedLine(line, mention.mentioned())
    }

    open fun handleLocalChat(senderUuid: UUID, rawMessage: String) {
        runOnMain {
            if (stopped) return@runOnMain
            val player = Bukkit.getPlayer(senderUuid)
            if (player == null || !player.isOnline()) return@runOnMain
            val username = player.getName()
            // Resolve the nick from EssentialsX directly: player.displayName() does
            // not reliably carry the nick's colours (especially hex) on this server.
            // Fall back to the vanilla display name when no nick is set.
            val displayName = NicknameComponentResolver.forPlayer(plugin.getEssentials(), player) ?: player.displayName()
            val rendered = renderLine(displayName, username, rawMessage, senderUuid)
            deliverLocally(rendered.line(), rendered.mentioned())
            publishPublicChat(senderUuid, username, rawMessage)
            publish(senderUuid, username, displayName, rawMessage)
            if (isGorkEnabled()) {
                val response = gorkManager.processMessage(rawMessage)
                if (response != null) {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        val responseLine = GorkManager.decorateMessage(response)
                        deliverLocally(responseLine, emptySet())
                        publishGork(responseLine)
                    }, 20L)
                }
            }
        }
    }

    /** Sends the line to all local players and pings mentioned players on the main thread. */
    open fun deliverLocally(line: Component, mentioned: Set<UUID>) {
        runOnMain {
            if (stopped) return@runOnMain
            for (player in Bukkit.getOnlinePlayers()) {
                player.sendMessage(line)
                if (soundEnabled && mentioned.contains(player.getUniqueId()) && mentionPingsEnabled(player.getUniqueId())) {
                    player.playSound(mentionSound, Sound.Emitter.self())
                }
            }
        }
    }

    /** Publishes a line with this process's server ID so receivers can drop echoes. */
    open fun publish(senderUuid: UUID, username: String, displayName: Component, rawMessage: String) {
        if (stopped) return
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        val envelope = JsonObject()
        envelope.addProperty("origin", serverId.toString())
        envelope.addProperty("uuid", senderUuid.toString())
        envelope.addProperty("username", username)
        envelope.addProperty("displayName", GsonComponentSerializer.gson().serialize(displayName))
        envelope.addProperty("message", rawMessage)
        val payload = envelope.toString()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                pool.resource.use { jedis -> jedis.publish(CHANNEL, payload) }
            } catch (e: Exception) {
                plugin.getLogger().warning("Global chat publish failed: " + e.message)
            }
        })
    }

    private fun publishGork(response: Component) {
        val pool = jedisPool
        if (stopped || pool == null || pool.isClosed) return
        val envelope = JsonObject()
        envelope.addProperty("type", "gork")
        envelope.addProperty("origin", serverId.toString())
        envelope.addProperty("component", GsonComponentSerializer.gson().serialize(response))
        val payload = envelope.toString()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                pool.resource.use { jedis -> jedis.publish(CHANNEL, payload) }
            } catch (e: Exception) {
                plugin.getLogger().warning("Gork response publish failed: " + e.message)
            }
        })
    }

    /** Publishes the accepted message as plain visible text for public consumers. */
    open fun publishPublicChat(senderUuid: UUID, username: String, rawMessage: String) {
        if (!publicChatEnabled || stopped) return
        val pool = jedisPool
        if (pool == null || pool.isClosed) return
        val fields = publicChatFields(senderUuid, username, rawMessage)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                pool.resource.use { jedis ->
                    jedis.xadd(publicChatStream, XAddParams.xAddParams()
                        .maxLen(publicChatMaxMessages).approximateTrimming(), fields)
                }
            } catch (e: Exception) {
                plugin.getLogger().warning("Public chat publish failed: " + e.message)
            }
        })
    }

    /** Parses an inbound envelope, drops our own echoes, then renders and delivers it. */
    private fun handleIncoming(payload: String) {
        if (stopped) return
        val envelope = JsonParser.parseString(payload).asJsonObject
        val origin = if (envelope.has("origin")) envelope.get("origin").asString else ""
        if (serverId.toString() == origin) return
        if ("gork" == if (envelope.has("type")) envelope.get("type").asString else "") {
            if (!isGorkEnabled()) return
            val componentJson = if (envelope.has("component")) envelope.get("component").asString else ""
            if (componentJson.isEmpty()) return
            val response = try {
                GsonComponentSerializer.gson().deserialize(componentJson)
            } catch (e: Exception) {
                plugin.getLogger().fine("Invalid Gork component received: " + e.message)
                return
            }
            runOnMain { deliverLocally(response, emptySet()) }
            return
        }
        val username = if (envelope.has("username")) envelope.get("username").asString else ""
        val message = if (envelope.has("message")) envelope.get("message").asString else ""
        val uuidStr = if (envelope.has("uuid")) envelope.get("uuid").asString else null
        val senderUuid = try {
            if (uuidStr == null) UUID(0L, 0L) else UUID.fromString(uuidStr)
        } catch (e: IllegalArgumentException) {
            UUID(0L, 0L)
        }
        val displayJson = if (envelope.has("displayName")) envelope.get("displayName").asString else null
        runOnMain {
            if (stopped) return@runOnMain
            val displayName = if (!displayJson.isNullOrEmpty()) {
                try {
                    GsonComponentSerializer.gson().deserialize(displayJson)
                } catch (e: Exception) {
                    Component.text(username)
                }
            } else {
                Component.text(username)
            }
            val rendered = renderLine(displayName, username, message, senderUuid)
            deliverLocally(rendered.line(), rendered.mentioned())
        }
    }

    /** Defaults to true until the settings service is available. */
    private fun mentionPingsEnabled(uuid: UUID): Boolean {
        val settings = plugin.getPlayerSettingsService()
        return settings == null || settings.isMentionPingsEnabled(uuid)
    }

    private fun runOnMain(task: Runnable) {
        if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
    }

    open fun shutdown() {
        stopped = true
        try {
            pubSub?.unsubscribe()
        } catch (ignored: Exception) {
        }
        subscriberThread?.interrupt()
        val pool = jedisPool
        if (pool != null && !pool.isClosed) {
            try {
                pool.close()
            } catch (ignored: NoClassDefFoundError) {
            }
            jedisPool = null
        }
    }

    companion object {
        private const val CHANNEL = "crabutilities:globalchat"
        private val URL_PATTERN = Pattern.compile("(?i)\\bhttps?://(?:[^\\s<>()\"']*[^\\s<>()\"'.,!?;:])")

        @JvmStatic
        fun linkifyUrls(message: Component): Component = message.replaceText { config ->
            config.match(URL_PATTERN).replacement { match, builder ->
                val url = match.group()
                try {
                    if (URI.create(url).host == null) return@replacement builder
                } catch (ignored: IllegalArgumentException) {
                    return@replacement builder
                }
                builder.clickEvent(ClickEvent.openUrl(url))
            }
        }

        private fun messageCommand(username: String): String = "/msg $username "

        @JvmStatic
        fun publicChatFields(senderUuid: UUID, username: String, rawMessage: String): Map<String, String> {
            val fields = LinkedHashMap<String, String>()
            fields["uuid"] = senderUuid.toString()
            fields["username"] = username
            fields["message"] = visiblePlainText(rawMessage)
            return fields
        }

        @JvmStatic
        fun visiblePlainText(rawMessage: String): String = PlainTextComponentSerializer.plainText()
            .serialize(SafeChatMiniMessage.deserialize(rawMessage))
    }
}
