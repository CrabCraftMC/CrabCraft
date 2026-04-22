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

import java.util.Map;

/**
 * Subscribes to {@code crabutilities:stats-push} and, for each message,
 * evaluates award scores and updates both the legacy
 * {@code player_season_stats} row and the award/medal tables.
 *
 * <p>Envelope shape (published by the Spigot {@code StatsPushTask}):
 * <pre>{"serverId":"survival","uuid":"&lt;uuid&gt;","stats":&lt;raw stats object&gt;}</pre>
 *
 * <p>Reconnects on Redis errors with a 3s backoff, same as the other
 * Redis subscribers in this plugin.
 */
public class StatsPushSubscriber {

    private static final String CHANNEL = "crabutilities:stats-push";
    private static final Gson GSON = new Gson();

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private final Logger logger;
    private JedisPool jedisPool;
    private Thread subscriberThread;
    private JedisPubSub pubSub;

    public StatsPushSubscriber(CrabUtilitiesVelocity plugin, VelocityConfig config, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);

        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort());
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            logger.info("StatsPushSubscriber listening on {}", CHANNEL);
        } catch (Exception e) {
            logger.error("StatsPushSubscriber failed to connect to Redis at {}:{}",
                    config.getRedisHost(), config.getRedisPort(), e);
            jedisPool.close();
            jedisPool = null;
            return;
        }

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                handleMessage(message);
            }
        };

        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(pubSub, CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
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

    private void handleMessage(String message) {
        JsonObject envelope;
        try {
            envelope = GSON.fromJson(message, JsonObject.class);
        } catch (JsonSyntaxException e) {
            logger.warn("Ignoring malformed stats-push envelope", e);
            return;
        }
        if (envelope == null) return;

        String serverId = envelope.has("serverId")
                ? envelope.get("serverId").getAsString() : null;
        String uuid = envelope.has("uuid")
                ? envelope.get("uuid").getAsString() : null;
        JsonObject stats = envelope.has("stats") && envelope.get("stats").isJsonObject()
                ? envelope.getAsJsonObject("stats") : null;

        if (serverId == null || uuid == null || stats == null) {
            logger.warn("Ignoring stats-push envelope with missing fields");
            return;
        }

        String season = plugin.getConfig().getCurrentSeason();

        // Legacy wide-row stats (unchanged schema). Parse the raw
        // JSON string since StatsParser takes text.
        try {
            String rawStatsJson = stats.toString();
            ComputedStats computed = StatsParser.parse(rawStatsJson);
            plugin.getPgWriter().writePlayerSeasonStats(uuid, season, computed);
        } catch (Exception e) {
            logger.warn("Failed to write player_season_stats for uuid={} server={}",
                    uuid, serverId, e);
        }

        // Award scores + medals.
        AwardEvaluator evaluator = plugin.getAwardEvaluator();
        AwardDbWriter writer = plugin.getAwardDbWriter();
        if (evaluator == null || writer == null) return;
        try {
            Map<String, Double> scores = evaluator.evaluate(stats);
            writer.writeForPlayerOnServer(uuid, season, serverId, scores);
        } catch (Exception e) {
            logger.warn("Failed to write award scores for uuid={} server={}",
                    uuid, serverId, e);
        }

        // Advancements.
        JsonObject advancements = envelope.has("advancements") && envelope.get("advancements").isJsonObject()
                ? envelope.getAsJsonObject("advancements") : null;
        if (advancements != null) {
            AdvancementDbWriter advWriter = plugin.getAdvancementDbWriter();
            if (advWriter != null) {
                try {
                    advWriter.writeForPlayerOnServer(uuid, season, serverId, advancements);
                } catch (Exception e) {
                    logger.warn("Failed to write advancements for uuid={} server={}",
                            uuid, serverId, e);
                }
            }
        }
    }

    public void shutdown() {
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {}
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (NoClassDefFoundError ignored) {}
        }
    }
}
