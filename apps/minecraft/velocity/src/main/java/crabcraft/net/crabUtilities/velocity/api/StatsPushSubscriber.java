package crabcraft.net.crabUtilities.velocity.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import crabcraft.net.crabUtilities.velocity.awards.AwardDbWriter;
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementDbWriter;
import crabcraft.net.crabUtilities.velocity.awards.AwardEvaluator;
import crabcraft.net.crabUtilities.velocity.db.ComputedStats;
import crabcraft.net.crabUtilities.velocity.db.StatsParser;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Subscribes to {@code crabutilities:stats-push} and, for each message,
 * evaluates award scores and updates both the legacy
 * {@code player_season_stats} row and the award/medal tables.
 *
 * <p>Envelope shape (published by the Spigot {@code StatsPushTask}):
 * <pre>{"season":"6","uuid":"&lt;uuid&gt;","stats":&lt;raw stats object&gt;}</pre>
 *
 * <p>Reconnects on Redis errors with a 3s backoff, same as the other
 * Redis subscribers in this plugin.
 */
public class StatsPushSubscriber {

    private static final String CHANNEL = "crabutilities:stats-push";
    private static final Gson GSON = new Gson();
    private static final int WORKER_THREADS = 2;
    private static final int WORK_QUEUE_SIZE = 512;
    private static final long MEDAL_RECOMPUTE_DELAY_SECONDS = 5L;

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private final Logger logger;
    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingMedalRecomputes =
            new ConcurrentHashMap<>();
    private JedisPool jedisPool;
    private Thread subscriberThread;
    private JedisPubSub pubSub;
    private ExecutorService statsExecutor;
    private ScheduledExecutorService medalExecutor;

    public StatsPushSubscriber(CrabUtilitiesVelocity plugin, VelocityConfig config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
    }

    public void start() {
        accepting.set(true);
        statsExecutor = createWorkerPool("CrabUtilities-Stats-Push", WORKER_THREADS, WORK_QUEUE_SIZE);
        medalExecutor = Executors.newSingleThreadScheduledExecutor(
                threadFactory("CrabUtilities-Award-Medals"));

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);

        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort());
        }

        logger.info("StatsPushSubscriber listening on {}; Redis will be retried asynchronously if unavailable.",
                CHANNEL);

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                enqueueMessage(message);
            }
        };

        subscriberThread = new Thread(() -> {
            while (accepting.get() && !Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    jedis.subscribe(pubSub, CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (!accepting.get() || Thread.currentThread().isInterrupted()) break;
                    logger.warn("Stats push subscriber disconnected, reconnecting in 3s...", e);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-Stats-Push-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private void enqueueMessage(String message) {
        if (!accepting.get()) return;
        ExecutorService executor = statsExecutor;
        if (executor == null || executor.isShutdown()) return;

        try {
            executor.execute(() -> {
                try {
                    processMessage(message);
                } catch (Exception e) {
                    logger.warn("Failed to process stats-push message", e);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warn("Dropping stats-push message because the processing queue is full");
        }
    }

    private void processMessage(String message) {
        JsonObject envelope;
        try {
            envelope = GSON.fromJson(message, JsonObject.class);
        } catch (JsonSyntaxException e) {
            logger.warn("Ignoring malformed stats-push envelope", e);
            return;
        }
        if (envelope == null) return;

        String uuid = envelope.has("uuid")
                ? envelope.get("uuid").getAsString() : null;
        JsonObject stats = envelope.has("stats") && envelope.get("stats").isJsonObject()
                ? envelope.getAsJsonObject("stats") : null;

        if (uuid == null || stats == null) {
            logger.warn("Ignoring stats-push envelope with missing fields");
            return;
        }

        // Season comes from the Spigot server's config via the envelope.
        // Fall back to DB current season for backwards compat with older Spigot plugins.
        String season = envelope.has("season") && envelope.get("season").isJsonPrimitive()
                ? envelope.get("season").getAsString() : null;
        if (season == null || season.isBlank()) {
            if (plugin.getAwardQueryService() != null) {
                season = plugin.getAwardQueryService().getCurrentSeason();
            }
        }
        if (season == null || season.isBlank()) {
            logger.warn("Skipping stats-push for uuid={}: no season in envelope and no current season in DB", uuid);
            return;
        }

        // Legacy wide-row stats (unchanged schema). Parse the raw
        // JSON string since StatsParser takes text.
        try {
            String rawStatsJson = stats.toString();
            ComputedStats computed = StatsParser.parse(rawStatsJson);
            if (plugin.getPgWriter() != null) {
                plugin.getPgWriter().writePlayerSeasonStats(uuid, season, computed);
            }
        } catch (Exception e) {
            logger.warn("Failed to write player_season_stats for uuid={}", uuid, e);
        }

        // Award scores + medals.
        AwardEvaluator evaluator = plugin.getAwardEvaluator();
        AwardDbWriter writer = plugin.getAwardDbWriter();
        if (evaluator != null && writer != null) {
            try {
                Map<String, Double> scores = evaluator.evaluate(stats);
                writer.writeScoresForPlayer(uuid, season, scores);
                queueMedalRecompute(season);
            } catch (Exception e) {
                logger.warn("Failed to write award scores for uuid={}", uuid, e);
            }
        }

        // Advancements.
        JsonObject advancements = envelope.has("advancements") && envelope.get("advancements").isJsonObject()
                ? envelope.getAsJsonObject("advancements") : null;
        if (advancements != null) {
            AdvancementDbWriter advWriter = plugin.getAdvancementDbWriter();
            if (advWriter != null) {
                try {
                    advWriter.writeForPlayer(uuid, season, advancements);
                } catch (Exception e) {
                    logger.warn("Failed to write advancements for uuid={}", uuid, e);
                }
            }
        }
    }

    private void queueMedalRecompute(String season) {
        if (season == null || season.isBlank()) return;
        ScheduledExecutorService scheduler = medalExecutor;
        if (scheduler == null || scheduler.isShutdown()) return;

        pendingMedalRecomputes.computeIfAbsent(season, key -> {
            try {
                return scheduler.schedule(() -> recomputeMedals(key),
                        MEDAL_RECOMPUTE_DELAY_SECONDS, TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                logger.warn("Failed to schedule medal recompute for season={}", key);
                return null;
            }
        });
    }

    private void recomputeMedals(String season) {
        pendingMedalRecomputes.remove(season);
        AwardDbWriter writer = plugin.getAwardDbWriter();
        if (writer == null) return;
        try {
            writer.recomputeMedals(season);
        } catch (Exception e) {
            logger.warn("Failed to recompute award medals for season={}", season, e);
        }
    }

    public void shutdown() {
        accepting.set(false);
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {}
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        shutdownExecutor(statsExecutor, "stats-push workers", 15);
        statsExecutor = null;

        List<String> pendingSeasons = drainPendingMedalSeasons();
        shutdownExecutor(medalExecutor, "award medal scheduler", 5);
        medalExecutor = null;
        for (String season : pendingSeasons) {
            recomputeMedals(season);
        }

        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (NoClassDefFoundError ignored) {}
        }
        jedisPool = null;
    }

    private List<String> drainPendingMedalSeasons() {
        List<String> seasons = new ArrayList<>(pendingMedalRecomputes.keySet());
        for (String season : seasons) {
            ScheduledFuture<?> future = pendingMedalRecomputes.remove(season);
            if (future != null) {
                future.cancel(false);
            }
        }
        return seasons;
    }

    private void shutdownExecutor(ExecutorService executor, String name, int timeoutSeconds) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warn("{} did not stop cleanly; interrupting queued work", name);
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("{} still has running work", name);
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService createWorkerPool(String name, int threads, int queueSize) {
        return new ThreadPoolExecutor(
                threads, threads,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                threadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory threadFactory(String name) {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
