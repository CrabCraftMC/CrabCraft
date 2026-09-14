package crabcraft.net.crabUtilities.bingo

import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Tracks the active card and atomically publishes newly completed tasks to Redis. */
class BingoManager(private val plugin: JavaPlugin) {
    private val miniMessage = MiniMessage.miniMessage()
    private val pending = ConcurrentLinkedQueue<PendingCompletion>()
    private val excludedWorlds = java.util.Set.copyOf(plugin.config.getStringList("bingo.excluded-worlds"))
    private val stream = plugin.config.getString("bingo.redis-stream", DEFAULT_STREAM)
    private val activeCardKey = plugin.config.getString("bingo.active-card-key", DEFAULT_ACTIVE_CARD_KEY)
    private val sourceBackend: String
    private val enabled = plugin.config.getBoolean("bingo.enabled", false)
    private var jedisPool: JedisPool? = null
    private var detectors: List<BingoDetector> = emptyList()
    private var activeCardRefreshTask: BukkitTask? = null
    private var completionFlushTask: BukkitTask? = null
    @Volatile private var activeCard: BingoActiveCard? = null
    @Volatile private var redisFailureLogged = false
    @Volatile private var running = false

    init {
        val configuredBackend = plugin.config.getString("bingo.source-backend", "")
        sourceBackend = if (configuredBackend.isNullOrBlank()) "unknown" else configuredBackend
    }

    fun start() {
        if (!enabled) return
        val host = plugin.config.getString("redis.host", "localhost")
        val port = plugin.config.getInt("redis.port", 6379)
        val password = plugin.config.getString("redis.password", "")
        val poolConfig = JedisPoolConfig()
        jedisPool = if (password.isNullOrEmpty()) JedisPool(poolConfig, host, port, 2_000)
        else JedisPool(poolConfig, host, port, 2_000, password)
        val cardOneDetector = HardBingoListener(plugin, ::isTracking, ::complete, ::logHornProgress)
        val cardTwoDetector = BingoCardTwoListener(plugin, ::isTracking, ::complete)
        detectors = listOf(
            cardOneDetector,
            cardTwoDetector,
            BingoCardThreeCoreListener(plugin, ::isTracking, ::complete),
            BingoCardThreeAdventureListener(plugin, ::isTracking, ::complete),
            BingoCardThreeCombatListener(plugin, ::isTracking, ::complete),
            BingoCardThreeChallengeListener(plugin, ::isTracking, ::complete),
            BingoCardFourMobListener(plugin, ::isTracking, ::complete),
            BingoCardFourWorldListener(plugin, ::isTracking, ::complete),
            BingoCardFourCombatListener(plugin, ::isTracking, ::complete),
            BingoCardFourMechanicsListener(plugin, ::isTracking, ::complete),
            BingoCardFiveWorldListener(plugin, ::isTracking, ::complete, ::activeCardId),
            BingoCardFiveMobListener(plugin, ::isTracking, ::complete, ::activeCardId),
            BingoCardFiveMechanicsListener(plugin, ::isTracking, ::complete),
            BingoCardFiveChallengeListener(plugin, ::isTracking, ::complete, ::activeCardId),
            BingoCardSixWorldListener(plugin, ::isTracking, ::complete, ::activeCardId),
            BingoCardSixMobListener(plugin, ::isTracking, ::complete, ::activeCardId),
            BingoCardSixMechanicsListener(plugin, ::isTracking, ::complete, ::activeCardId),
        )
        detectors.forEach { detector -> plugin.server.pluginManager.registerEvents(detector, plugin) }
        running = true
        activeCardRefreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin, Runnable { refreshActiveCard() }, 0L, 20L * 30L)
        completionFlushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin, Runnable { flushPending() }, 20L, 20L)
        plugin.logger.info("Bingo tracking enabled; waiting for the active card from Redis.")
    }

    @Synchronized
    fun shutdown() {
        running = false
        activeCardRefreshTask?.cancel()
        activeCardRefreshTask = null
        completionFlushTask?.cancel()
        completionFlushTask = null
        activeCard = null
        flushPending()
        for (detector in detectors) {
            detector.clear()
            HandlerList.unregisterAll(detector)
        }
        detectors = emptyList()
        jedisPool?.close()
        jedisPool = null
    }

    fun complete(player: Player, task: BingoTask) {
        val card = activeCard
        if (!isEligible(player) || card == null || !card.isLive() || !card.contains(task)) return
        if (pending.size >= MAX_PENDING_COMPLETIONS) {
            plugin.logger.severe("Bingo completion queue is full; rejecting " + player.uniqueId)
            return
        }
        pending.add(PendingCompletion(card, player.uniqueId, task, Instant.now().epochSecond))
    }

    fun isEligible(player: Player): Boolean {
        val card = activeCard
        return enabled && running && player.gameMode == GameMode.SURVIVAL
            && !excludedWorlds.contains(player.world.name) && card != null && card.isLive()
    }

    fun isTracking(player: Player, task: BingoTask): Boolean {
        val card = activeCard
        return isEligible(player) && card != null && card.contains(task)
    }

    private fun activeCardId(): Int = activeCard?.id() ?: Int.MIN_VALUE

    private fun logHornProgress(player: Player, progress: HardBingoListener.HornProgress) {
        plugin.logger.info("Bingo goat horn progress for " + player.name
            + ": " + progress.uniqueCount() + "/5 (" + progress.instrument() + ")")
    }

    @Synchronized
    private fun refreshActiveCard() {
        val pool = jedisPool
        if (!running || pool == null) return
        try {
            pool.resource.use { jedis ->
                val json = jedis.get(activeCardKey)
                var next = if (json == null) null else BingoActiveCard.fromJson(json)
                if (next != null) {
                    val unsupported = next.taskIds().filter { taskId -> BingoTask.fromId(taskId).isEmpty }
                    if (next.taskIds().size != 16 || unsupported.isNotEmpty()) {
                        plugin.logger.severe("Refusing Bingo #" + next.number()
                            + ": expected 16 deployed task detectors; unsupported=" + unsupported)
                        next = null
                    }
                }
                if (running) {
                    val fetched = next
                    Bukkit.getScheduler().runTask(plugin, Runnable { applyActiveCard(fetched) })
                }
                redisRecovered()
            }
        } catch (e: Exception) {
            logRedisFailure("load the active bingo card", e)
        }
    }

    private fun applyActiveCard(next: BingoActiveCard?) {
        if (!running || activeCard == next) return
        detectors.forEach(BingoDetector::clear)
        activeCard = next
        if (next != null) {
            plugin.logger.info("Tracking Bingo #" + next.number() + " with " + next.taskIds().size + " tasks.")
        } else {
            plugin.logger.info("No supported active bingo card; tracking paused.")
        }
    }

    @Synchronized
    private fun flushPending() {
        val pool = jedisPool ?: return
        while (true) {
            val completion = pending.peek() ?: break
            try {
                pool.resource.use { jedis ->
                    val progressKey = "crabcraft:bingo:progress:" + completion.card.id() + ":" + completion.playerId
                    val result = jedis.eval(
                        RECORD_COMPLETION_SCRIPT,
                        java.util.List.of(progressKey, stream),
                        java.util.List.of(
                            completion.task.id(),
                            (completion.card.endsAt() + 86_400L).toString(),
                            completion.card.id().toString(),
                            completion.playerId.toString(),
                            completion.completedAt.toString(),
                            sourceBackend,
                        ),
                    )
                    pending.remove(completion)
                    if (result == 1L) sendCompletionMessage(completion)
                    redisRecovered()
                }
            } catch (e: Exception) {
                logRedisFailure("publish bingo completions", e)
                return
            }
        }
    }

    private fun sendCompletionMessage(completion: PendingCompletion) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val player = Bukkit.getPlayer(completion.playerId)
            if (player == null || !player.isOnline) return@Runnable
            if (plugin is crabcraft.net.crabUtilities.CrabUtilities
                && !plugin.isBingoMessagesEnabled(completion.playerId)) return@Runnable
            val message = miniMessage.deserialize(
                "<#b0b0b0>Completed</#b0b0b0> "
                    + "<#FCD05C>" + completion.task.description() + "</#FCD05C>")
                .decoration(TextDecoration.ITALIC, false)
            player.sendMessage(message)
        })
    }

    private fun redisRecovered() {
        if (redisFailureLogged) {
            plugin.logger.info("Bingo Redis connection recovered.")
            redisFailureLogged = false
        }
    }

    private fun logRedisFailure(action: String, error: Exception) {
        if (!redisFailureLogged) {
            plugin.logger.warning("Unable to " + action + "; retrying without blocking the server: " + error.message)
            redisFailureLogged = true
        }
    }

    private data class PendingCompletion(
        val card: BingoActiveCard,
        val playerId: UUID,
        val task: BingoTask,
        val completedAt: Long,
    )

    companion object {
        private const val DEFAULT_STREAM = "crabcraft:bingo:completions"
        private const val DEFAULT_ACTIVE_CARD_KEY = "crabcraft:bingo:active-card"
        private const val MAX_PENDING_COMPLETIONS = 10_000
        private val RECORD_COMPLETION_SCRIPT = """
            if redis.call('SADD', KEYS[1], ARGV[1]) == 0 then return 0 end
            redis.call('EXPIREAT', KEYS[1], ARGV[2])
            redis.call('XADD', KEYS[2], 'MAXLEN', '~', 100000, '*',
                'card_id', ARGV[3],
                'minecraft_uuid', ARGV[4],
                'task_id', ARGV[1],
                'completed_at', ARGV[5],
                'source_backend', ARGV[6])
            return 1
            """.trimIndent() + "\n"
    }
}
