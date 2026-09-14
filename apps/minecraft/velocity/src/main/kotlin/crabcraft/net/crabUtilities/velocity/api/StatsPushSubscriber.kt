package crabcraft.net.crabUtilities.velocity.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.RedisPools
import crabcraft.net.crabUtilities.velocity.VelocityConfig
import crabcraft.net.crabUtilities.velocity.db.StatsParser
import org.slf4j.Logger
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Subscribes to crabutilities:stats-push, evaluates award scores and updates
 * player_season_stats and the award/medal tables. Reconnects with a 3s backoff.
 */
open class StatsPushSubscriber(private val plugin: CrabUtilitiesVelocity, private val config: VelocityConfig, private val logger: Logger) {
    private val accepting = AtomicBoolean(false)
    private val pendingMedalRecomputes: ConcurrentMap<String, ScheduledFuture<*>> = ConcurrentHashMap()
    private var jedisPool: JedisPool? = null
    private var subscriberThread: Thread? = null
    private var pubSub: JedisPubSub? = null
    private var statsExecutor: ExecutorService? = null
    private var medalExecutor: ScheduledExecutorService? = null

    open fun start() {
        accepting.set(true)
        statsExecutor = createWorkerPool("CrabUtilities-Stats-Push", WORKER_THREADS, WORK_QUEUE_SIZE)
        medalExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory("CrabUtilities-Award-Medals"))
        jedisPool = RedisPools.create(config, 2)
        logger.info("StatsPushSubscriber listening on {}; Redis will be retried asynchronously if unavailable.", CHANNEL)
        pubSub = object : JedisPubSub() {
            override fun onMessage(channel: String, message: String) { enqueueMessage(message) }
        }
        subscriberThread = Thread({
            while (accepting.get() && !Thread.currentThread().isInterrupted) {
                val pool = jedisPool
                if (pool == null || pool.isClosed) break
                try {
                    pool.resource.use { jedis -> jedis.subscribe(pubSub, CHANNEL) }
                } catch (e: NoClassDefFoundError) {
                    break
                } catch (e: Exception) {
                    if (!accepting.get() || Thread.currentThread().isInterrupted) break
                    logger.warn("Stats push subscriber disconnected, reconnecting in 3s...", e)
                    try {
                        Thread.sleep(3000)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }, "CrabUtilities-Stats-Push-Subscriber")
        subscriberThread!!.isDaemon = true
        subscriberThread!!.start()
    }

    private fun enqueueMessage(message: String) {
        if (!accepting.get()) return
        val executor = statsExecutor
        if (executor == null || executor.isShutdown) return
        try {
            executor.execute {
                try {
                    processMessage(message)
                } catch (e: Exception) {
                    logger.warn("Failed to process stats-push message", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            logger.warn("Dropping stats-push message because the processing queue is full")
        }
    }

    private fun processMessage(message: String) {
        val envelope: JsonObject?
        try {
            envelope = GSON.fromJson(message, JsonObject::class.java)
        } catch (e: JsonSyntaxException) {
            logger.warn("Ignoring malformed stats-push envelope", e)
            return
        }
        if (envelope == null) return
        val uuid = if (envelope.has("uuid")) envelope.get("uuid").asString else null
        val stats = if (envelope.has("stats") && envelope.get("stats").isJsonObject) envelope.getAsJsonObject("stats") else null
        val customMetrics = if (envelope.has("custom") && envelope.get("custom").isJsonObject) envelope.getAsJsonObject("custom") else null
        val advancements = if (envelope.has("advancements") && envelope.get("advancements").isJsonObject) envelope.getAsJsonObject("advancements") else null
        if (uuid == null || stats == null) {
            logger.warn("Ignoring stats-push envelope with missing fields")
            return
        }

        // Season comes from the backend config, falling back to DB for older plugins.
        var season = if (envelope.has("season") && envelope.get("season").isJsonPrimitive) envelope.get("season").asString else null
        if (season == null || season.codePoints().allMatch(Character::isWhitespace)) {
            if (plugin.getAwardQueryService() != null) season = plugin.getAwardQueryService()!!.getCurrentSeason()
        }
        if (season == null || season.codePoints().allMatch(Character::isWhitespace)) {
            logger.warn("Skipping stats-push for uuid={}: no season in envelope and no current season in DB", uuid)
            return
        }

        // Legacy wide-row stats (unchanged schema).
        try {
            val computed = StatsParser.parse(stats)
            if (plugin.getPgWriter() != null) plugin.getPgWriter()!!.writePlayerSeasonStats(uuid, season, computed)
        } catch (e: Exception) {
            logger.warn("Failed to write player_season_stats for uuid={}", uuid, e)
        }

        // Award scores + medals.
        val evaluator = plugin.getAwardEvaluator()
        val writer = plugin.getAwardDbWriter()
        if (evaluator != null && writer != null) {
            try {
                val scores = evaluator.evaluate(stats, customMetrics, advancements)
                writer.writeScoresForPlayer(uuid, season, scores)
                queueMedalRecompute(season)
            } catch (e: Exception) {
                logger.warn("Failed to write award scores for uuid={}", uuid, e)
            }
        }

        // Advancements.
        if (advancements != null) {
            val advWriter = plugin.getAdvancementDbWriter()
            if (advWriter != null) {
                try {
                    advWriter.writeForPlayer(uuid, season, advancements)
                } catch (e: Exception) {
                    logger.warn("Failed to write advancements for uuid={}", uuid, e)
                }
            }
        }
    }

    private fun queueMedalRecompute(season: String?) {
        if (season == null || season.codePoints().allMatch(Character::isWhitespace)) return
        val scheduler = medalExecutor
        if (scheduler == null || scheduler.isShutdown) return
        pendingMedalRecomputes.computeIfAbsent(season) { key ->
            try {
                scheduler.schedule({ recomputeMedals(key) }, MEDAL_RECOMPUTE_DELAY_SECONDS, TimeUnit.SECONDS)
            } catch (e: RejectedExecutionException) {
                logger.warn("Failed to schedule medal recompute for season={}", key)
                null
            }
        }
    }

    private fun recomputeMedals(season: String) {
        pendingMedalRecomputes.remove(season)
        val writer = plugin.getAwardDbWriter() ?: return
        try {
            writer.recomputeMedals(season)
        } catch (e: Exception) {
            logger.warn("Failed to recompute award medals for season={}", season, e)
        }
    }

    open fun shutdown() {
        accepting.set(false)
        if (pubSub != null) {
            try { pubSub!!.unsubscribe() } catch (ignored: Exception) {}
        }
        if (subscriberThread != null) {
            subscriberThread!!.interrupt()
            try {
                subscriberThread!!.join(2000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        shutdownExecutor(statsExecutor, "stats-push workers", 15)
        statsExecutor = null
        val pendingSeasons = drainPendingMedalSeasons()
        shutdownExecutor(medalExecutor, "award medal scheduler", 5)
        medalExecutor = null
        for (season in pendingSeasons) recomputeMedals(season)
        if (jedisPool != null && !jedisPool!!.isClosed) {
            try { jedisPool!!.close() } catch (ignored: NoClassDefFoundError) {}
        }
        jedisPool = null
    }

    private fun drainPendingMedalSeasons(): List<String> {
        val seasons = ArrayList(pendingMedalRecomputes.keys)
        for (season in seasons) pendingMedalRecomputes.remove(season)?.cancel(false)
        return seasons
    }

    private fun shutdownExecutor(executor: ExecutorService?, name: String, timeoutSeconds: Int) {
        if (executor == null) return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                logger.warn("{} did not stop cleanly; interrupting queued work", name)
                executor.shutdownNow()
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) logger.warn("{} still has running work", name)
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val CHANNEL = "crabutilities:stats-push"
        private val GSON = Gson()
        private const val WORKER_THREADS = 2
        private const val WORK_QUEUE_SIZE = 512
        private const val MEDAL_RECOMPUTE_DELAY_SECONDS = 5L

        private fun createWorkerPool(name: String, threads: Int, queueSize: Int): ExecutorService =
            ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(queueSize),
                threadFactory(name), ThreadPoolExecutor.AbortPolicy())

        private fun threadFactory(name: String): ThreadFactory {
            val count = AtomicInteger()
            return ThreadFactory { task ->
                val thread = Thread(task, name + "-" + count.incrementAndGet())
                thread.isDaemon = true
                thread
            }
        }
    }
}
