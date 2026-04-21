package crabcraft.net.crabUtilities.velocity.api;

import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires stats-request messages to all backend servers on Redis and
 * collects their per-server responses within a time window.
 *
 * <p>Wire format:
 * <ul>
 *   <li>Request:  {@code requestId \0 uuid} on
 *       {@code crabutilities:stats-request}.</li>
 *   <li>Response: {@code requestId \0 serverId \0 statsJson} on
 *       {@code crabutilities:stats-response}. Empty statsJson means the
 *       sending backend has no stats file for that uuid.</li>
 * </ul>
 *
 * <p>{@link #requestStats(String)} resolves to a map of
 * {@code serverId -> statsJson} once the collection window closes. The
 * future always completes, even if no backend responds (with an empty
 * map).
 */
public class StatsRequestManager {

    private static final String REQUEST_CHANNEL = "crabutilities:stats-request";
    private static final String RESPONSE_CHANNEL = "crabutilities:stats-response";
    private static final String SEPARATOR = "\0";
    private static final long COLLECT_WINDOW_MS = 1500;

    private final VelocityConfig config;
    private final Logger logger;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private JedisPool jedisPool;
    private Thread subscriberThread;
    private JedisPubSub pubSub;
    private ScheduledExecutorService scheduler;

    public StatsRequestManager(VelocityConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);

        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort());
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            logger.info("StatsRequestManager connected to Redis at {}:{}", config.getRedisHost(), config.getRedisPort());
        } catch (Exception e) {
            logger.error("StatsRequestManager failed to connect to Redis at {}:{}", config.getRedisHost(), config.getRedisPort(), e);
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CrabUtilities-Stats-Collector");
            t.setDaemon(true);
            return t;
        });

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                int first = message.indexOf(SEPARATOR);
                if (first == -1) return;
                String requestId = message.substring(0, first);
                String rest = message.substring(first + 1);
                int second = rest.indexOf(SEPARATOR);
                if (second == -1) return; // old-format response without serverId
                String serverId = rest.substring(0, second);
                String data = rest.substring(second + 1);
                Pending p = pending.get(requestId);
                if (p == null) return;
                if (!data.isEmpty() && !serverId.isEmpty()) {
                    p.responses.put(serverId, data);
                }
            }
        };

        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(pubSub, RESPONSE_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    logger.warn("Stats Redis subscriber disconnected, reconnecting in 3s...", e);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-Stats-Response-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    /**
     * Request a player's stats from every backend and resolve to whatever
     * responded within the collection window. Never completes
     * exceptionally — backends that never respond simply aren't in the
     * returned map.
     */
    public CompletableFuture<Map<String, String>> requestStats(String uuid) {
        String requestId = UUID.randomUUID().toString();
        Pending p = new Pending();
        pending.put(requestId, p);

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(REQUEST_CHANNEL, requestId + SEPARATOR + uuid);
        } catch (Exception e) {
            logger.error("Failed to publish stats request to Redis", e);
            pending.remove(requestId);
            p.future.complete(new HashMap<>());
            return p.future;
        }

        scheduler.schedule(() -> {
            Pending removed = pending.remove(requestId);
            if (removed != null) {
                removed.future.complete(new HashMap<>(removed.responses));
            }
        }, COLLECT_WINDOW_MS, TimeUnit.MILLISECONDS);

        return p.future;
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
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (NoClassDefFoundError ignored) {}
        }
    }

    private static final class Pending {
        final CompletableFuture<Map<String, String>> future = new CompletableFuture<>();
        final ConcurrentHashMap<String, String> responses = new ConcurrentHashMap<>();
    }
}
