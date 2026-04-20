package crabcraft.net.crabUtilities.velocity.api;

import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class StatsRequestManager {

    private static final String REQUEST_CHANNEL = "crabutilities:stats-request";
    private static final String RESPONSE_CHANNEL = "crabutilities:stats-response";
    private static final String SEPARATOR = "\0";

    private final VelocityConfig config;
    private final Logger logger;
    private final ConcurrentHashMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private JedisPool jedisPool;
    private Thread subscriberThread;
    private JedisPubSub pubSub;

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

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                int sep = message.indexOf(SEPARATOR);
                if (sep == -1) return;
                String requestId = message.substring(0, sep);
                String data = message.substring(sep + 1);
                CompletableFuture<String> future = pending.remove(requestId);
                if (future != null) {
                    future.complete(data.isEmpty() ? null : data);
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

    public CompletableFuture<String> requestStats(String uuid) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(requestId, future);
        future.whenComplete((r, e) -> pending.remove(requestId));
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(REQUEST_CHANNEL, requestId + SEPARATOR + uuid);
        } catch (Exception e) {
            logger.error("Failed to publish stats request to Redis", e);
            pending.remove(requestId);
            future.completeExceptionally(e);
        }
        return future;
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
