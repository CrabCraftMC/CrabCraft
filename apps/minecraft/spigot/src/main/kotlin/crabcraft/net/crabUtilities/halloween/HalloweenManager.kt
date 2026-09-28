package crabcraft.net.crabUtilities.halloween

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.bingo.BingoCardTwoListener
import crabcraft.net.crabUtilities.bingo.BingoTask
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.ZombieVillager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTransformEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub

/** Sends ordered Halloween actions; the bot stores hunt progress and awards the role. */
class HalloweenManager(private val plugin: JavaPlugin) : Listener {
    private val excludedWorlds = plugin.config.getStringList("halloween.excluded-worlds").toSet()
    private val pending = ConcurrentLinkedQueue<PendingAction>()
    private val bellDetector =
        BingoCardTwoListener(
            plugin,
            { player, task -> task == BingoTask.CREEPER_RINGS_BELL && eligible(player) },
            { player, _ -> record(player, "trick_or_treat") },
        )
    private var pool: JedisPool? = null
    private var refreshTask: BukkitTask? = null
    private var flushTask: BukkitTask? = null
    private var completionThread: Thread? = null
    @Volatile private var completionSubscriber: JedisPubSub? = null
    @Volatile private var running = false
    private var failureLogged = false
    private var activeEvent: ActiveEvent? = null
    private val progressRequests = HashSet<UUID>()

    fun start() {
        val password = plugin.config.getString("redis.password", "")
        pool =
            JedisPool(
                JedisPoolConfig(),
                plugin.config.getString("redis.host", "localhost"),
                plugin.config.getInt("redis.port", 6379),
                2_000,
                password?.takeUnless { it.isEmpty() },
            )
        running = true
        completionThread =
            Thread(::listenForCompletions, "Halloween completions").apply {
                isDaemon = true
                start()
            }
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.pluginManager.registerEvents(bellDetector, plugin)
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable(::refresh), 0L, 20L * 10)
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable(::flush), 20L, 20L)
    }

    private fun listenForCompletions() {
        while (running) {
            try {
                pool!!.resource.use { jedis ->
                    completionSubscriber =
                        object : JedisPubSub() {
                            override fun onMessage(channel: String, message: String) {
                                try {
                                    val completion = JsonParser.parseString(message).asJsonObject
                                    val playerId = UUID.fromString(completion.get("minecraftUuid").asString)
                                    val task =
                                        when (completion.get("task").asString) {
                                            "pumpkin_hunt" -> "Pumpkin Head’s Hunt"
                                            "trick_or_treat" -> "Trick or Treat"
                                            "back_from_the_dead" -> "Back from the Dead"
                                            else -> null
                                        }
                                    if (task == null || !running) return
                                    val count = completion.get("completedTasks").asInt
                                    Bukkit.getScheduler()
                                        .runTask(
                                            plugin,
                                            Runnable {
                                                if (!running) return@Runnable
                                                val player = Bukkit.getPlayer(playerId) ?: return@Runnable
                                                player.sendMessage(
                                                    Component.text("Completed ", TextColor.color(0xFF8C32))
                                                        .append(Component.text("$task! ", TextColor.color(0xFFC65A)))
                                                        .append(Component.text("($count/3)", TextColor.color(0xADCA88)))
                                                )
                                                if (count == 3) {
                                                    player.sendMessage(
                                                        Component.text(
                                                                "You've earned an exclusive Discord role and an in-game pumpkin tag!",
                                                                TextColor.color(0xFFC65A),
                                                            )
                                                            .hoverEvent(
                                                                HoverEvent.showText(
                                                                    CrabMessages.text(
                                                                        "Link your Minecraft account on Discord to receive the role." +
                                                                            "\nThe in-game tag lasts while the event is active."
                                                                    )
                                                                )
                                                            )
                                                    )
                                                }
                                            },
                                        )
                                } catch (error: Exception) {
                                    if (running) plugin.logger.warning("Invalid Halloween completion: ${error.message}")
                                }
                            }

                            override fun onSubscribe(channel: String, subscribedChannels: Int) {
                                if (!running) unsubscribe()
                            }
                        }
                    if (running) jedis.subscribe(completionSubscriber, "crabcraft:halloween:completions")
                }
            } catch (error: Exception) {
                if (running) logFailure(error)
            }
            if (running) {
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /** All Redis work runs asynchronously; only the requesting player receives the reply. */
    fun showProgress(player: Player) {
        val playerId = player.uniqueId
        if (!running || !progressRequests.add(playerId)) return
        Bukkit.getScheduler()
            .runTaskAsynchronously(
                plugin,
                Runnable {
                    var response: JsonObject? = null
                    try {
                        pool!!.resource.use { jedis ->
                            val requestId = UUID.randomUUID().toString()
                            val request =
                                JsonObject().apply {
                                    addProperty("requestId", requestId)
                                    addProperty("playerId", playerId.toString())
                                    addProperty("expiresAt", System.currentTimeMillis() + 10_000)
                                }
                            jedis.rpush("crabcraft:halloween:progress-requests", request.toString())
                            val reply = jedis.blpop(5, "crabcraft:halloween:progress-reply:$requestId")
                            if (reply != null) response = JsonParser.parseString(reply[1]).asJsonObject
                        }
                    } catch (error: Exception) {
                        logFailure(error)
                    }
                    val result = response
                    if (running)
                        Bukkit.getScheduler()
                            .runTask(
                                plugin,
                                Runnable {
                                    progressRequests.remove(playerId)
                                    if (!running || !player.isOnline) return@Runnable
                                    when {
                                        result == null ->
                                            player.sendMessage(
                                                CrabMessages.error(
                                                    "Halloween progress is temporarily unavailable. Please try again."
                                                )
                                            )
                                        result.has("unavailable") ->
                                            player.sendMessage(
                                                CrabMessages.muted("The Halloween event is currently unavailable.")
                                            )
                                        else -> sendProgress(player, result)
                                    }
                                },
                            )
                },
            )
    }

    private fun sendProgress(player: Player, progress: JsonObject) {
        val now = System.currentTimeMillis() / 1000
        val start = progress.get("startsAt").asLong
        val end = progress.get("endsAt").asLong
        val date = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm z", Locale.UK).withZone(ZoneId.of("Europe/London"))
        val mask = progress.get("huntMask").asInt
        val mobs = listOf("zombie", "skeleton", "spider", "creeper", "witch")
        val remaining = mobs.filterIndexed { index, _ -> mask and (1 shl index) == 0 }
        val trick = progress.get("trickOrTreat").asBoolean
        val cure = progress.get("backFromTheDead").asBoolean
        val completed = (if (mask == 31) 1 else 0) + (if (trick) 1 else 0) + (if (cure) 1 else 0)
        val timing =
            when {
                now < start -> "Starts ${date.format(Instant.ofEpochSecond(start))}"
                now >= end -> "The event has ended."
                else -> "Ends ${date.format(Instant.ofEpochSecond(end))}"
            }
        player.sendMessage(
            CrabMessages.accent("Halloween · $completed/3 complete")
                .append(CrabMessages.muted(" (hover)"))
                .hoverEvent(
                    HoverEvent.showText(
                        CrabMessages.text(
                            timing +
                                "\nComplete all three on this account for the Discord role." +
                                "\nLink your Discord account to receive it." +
                                "\nProgress can take a few seconds to update."
                        )
                    )
                )
        )
        player.sendMessage(
            (if (mask == 31) CrabMessages.success("✓ Pumpkin Head’s Hunt · 5/5")
                else CrabMessages.text("○ Pumpkin Head’s Hunt · ${5 - remaining.size}/5"))
                .hoverEvent(
                    HoverEvent.showText(
                        CrabMessages.text(
                            "Wear a carved pumpkin and kill a zombie, skeleton, spider, creeper and witch without dying." +
                                (if (remaining.isEmpty()) "" else "\nRemaining: ${remaining.joinToString(", ")}") +
                                "\nDeath only resets an unfinished hunt."
                        )
                    )
                )
        )
        player.sendMessage(
            (if (trick) CrabMessages.success("✓ Trick or Treat") else CrabMessages.text("○ Trick or Treat")).hoverEvent(
                HoverEvent.showText(
                    CrabMessages.text(
                        "Place a pressure plate directly beside a bell." +
                            "\nGet a creeper to step on it and ring the bell." +
                            "\nYou must place the plate yourself."
                    )
                )
            )
        )
        player.sendMessage(
            (if (cure) CrabMessages.success("✓ Back from the Dead") else CrabMessages.text("○ Back from the Dead"))
                .hoverEvent(
                    HoverEvent.showText(
                        CrabMessages.text(
                            "Cure a zombie villager. Stay online in Survival and wear a carved pumpkin when the cure finishes."
                        )
                    )
                )
        )
    }

    private fun live(): Boolean {
        val now = System.currentTimeMillis() / 1000
        val event = activeEvent
        return running && event != null && event.startsAt <= now && now < event.endsAt
    }

    private fun eligible(player: Player): Boolean =
        live() && player.gameMode == GameMode.SURVIVAL && player.world.name !in excludedWorlds

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMobDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer
        val mob = event.entityType.name.lowercase(Locale.ROOT)
        if (
            killer != null &&
                mob in HUNT_MOBS &&
                eligible(killer) &&
                wearsPumpkin(killer) &&
                event.entity.world.name !in excludedWorlds
        )
            record(killer, mob)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        // Removing the pumpkin or travelling to an excluded world cannot bypass a reset.
        if (live() && event.entity.gameMode == GameMode.SURVIVAL) record(event.entity, "death")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCure(event: EntityTransformEvent) {
        val zombie = event.entity as? ZombieVillager ?: return
        if (event.transformReason != EntityTransformEvent.TransformReason.CURED || zombie.world.name in excludedWorlds)
            return
        val conversionPlayer = zombie.conversionPlayer ?: return
        val player = Bukkit.getPlayer(conversionPlayer.uniqueId)
        if (player != null && eligible(player) && wearsPumpkin(player)) record(player, "back_from_the_dead")
    }

    private fun record(player: Player, action: String) {
        if (!live()) return
        pending.add(
            PendingAction(
                UUID.randomUUID().toString(),
                activeEvent!!.id,
                player.uniqueId.toString(),
                action,
                System.currentTimeMillis() / 1000,
            )
        )
    }

    private fun refresh() {
        try {
            pool!!.resource.use { jedis ->
                val fetched =
                    jedis.get(ACTIVE_KEY)?.let { json ->
                        val obj = JsonParser.parseString(json).asJsonObject
                        ActiveEvent(obj.get("id").asString, obj.get("startsAt").asLong, obj.get("endsAt").asLong)
                    }
                if (running)
                    Bukkit.getScheduler()
                        .runTask(
                            plugin,
                            Runnable {
                                if (!running) return@Runnable
                                if (activeEvent != fetched) bellDetector.clear()
                                activeEvent = fetched
                            },
                        )
            }
        } catch (e: Exception) {
            logFailure(e)
        }
    }

    @Synchronized
    private fun flush() {
        val pool = pool ?: return
        if (pending.isEmpty()) return
        try {
            pool.resource.use { jedis ->
                while (true) {
                    val action = pending.peek() ?: break
                    // Retries after a lost Redis reply cannot replay a kill after a later death.
                    jedis.eval(
                        PUBLISH_SCRIPT,
                        listOf("crabcraft:halloween:sent:${action.id}", STREAM),
                        listOf(action.eventId, action.playerId, action.action, action.occurredAt.toString()),
                    )
                    pending.remove(action)
                }
                failureLogged = false
            }
        } catch (e: Exception) {
            logFailure(e)
        }
    }

    @Synchronized
    private fun logFailure(error: Exception) {
        if (!failureLogged) {
            plugin.logger.warning("Halloween Redis connection unavailable; retrying: ${error.message}")
            failureLogged = true
        }
    }

    fun shutdown() {
        running = false
        val subscriber = completionSubscriber
        if (subscriber != null && subscriber.isSubscribed) {
            try {
                subscriber.unsubscribe()
            } catch (error: Exception) {
                plugin.logger.fine("Halloween subscription already disconnected: ${error.message}")
            }
        }
        completionThread?.interrupt()
        refreshTask?.cancel()
        flushTask?.cancel()
        HandlerList.unregisterAll(this)
        HandlerList.unregisterAll(bellDetector)
        bellDetector.clear()
        flush()
        if (pending.isNotEmpty()) plugin.logger.severe("Halloween stopped with unpublished actions: ${pending.size}")
        pool?.close()
    }

    private data class ActiveEvent(val id: String, val startsAt: Long, val endsAt: Long)

    private data class PendingAction(
        val id: String,
        val eventId: String,
        val playerId: String,
        val action: String,
        val occurredAt: Long,
    )

    companion object {
        private const val STREAM = "crabcraft:halloween:actions"
        private const val ACTIVE_KEY = "crabcraft:halloween:active-event"
        private val HUNT_MOBS = setOf("zombie", "skeleton", "spider", "creeper", "witch")
        private val PUBLISH_SCRIPT =
            """
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('XADD', KEYS[2], '*',
                'event_id', ARGV[1], 'minecraft_uuid', ARGV[2],
                'action', ARGV[3], 'occurred_at', ARGV[4])
            redis.call('SET', KEYS[1], '1', 'EX', 604800)
            return 1
            """
                .trimIndent() + "\n"

        private fun wearsPumpkin(player: Player): Boolean = player.inventory.helmet?.type == Material.CARVED_PUMPKIN
    }
}
