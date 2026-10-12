package crabcraft.net.crabUtilities.velocity.db;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerStatusService {

    public static final String HASH_KEY = "crabutilities:player-status";
    public static final String UPDATE_CHANNEL = "crabutilities:player-status-updates";

    private final Logger logger;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;

    private final ConcurrentHashMap<UUID, AfkStatus> cache = new ConcurrentHashMap<>();

    private JedisPool jedisPool;
    private SubscriberThread subscriberThread;

    public PlayerStatusService(Logger logger, String redisHost, int redisPort, String redisPassword) {
        this.logger = logger;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
    }

    private static AfkStatus preferNewer(AfkStatus cached, AfkStatus incoming) {
        return incoming.changedAt >= cached.changedAt ? incoming : cached;
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxIdle(2);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword);
        } else {
            this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000);
        }

        this.subscriberThread = new SubscriberThread();
        this.subscriberThread.setName("crabutilities-player-status-subscriber");
        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();

        logger.info("Player status service started.");
    }

    public void shutdown() {
        SubscriberThread subscriber = this.subscriberThread;

        if (subscriber != null) {
            subscriber.cancelled = true;

            try {
                if (subscriber.subscriber != null && subscriber.subscriber.isSubscribed()) {
                    subscriber.subscriber.unsubscribe();
                }
            } catch (Exception ignored) {
            }

            subscriber.interrupt();
            this.subscriberThread = null;
        }

        JedisPool pool = this.jedisPool;
        this.jedisPool = null;

        if (pool != null && !pool.isClosed()) {
            try {
                pool.close();
            } catch (NoClassDefFoundError ignored) {
            }
        }

        cache.clear();
    }

    /**
     * Returns the current cached AFK state.
     *
     * <p>Returns null if Velocity has not received a status for this UUID.
     */
    public AfkStatus get(UUID uuid) {
        return cache.get(uuid);
    }

    /**
     * Convenience method for API callers.
     */
    public boolean isAfk(UUID uuid) {
        AfkStatus status = cache.get(uuid);
        return status != null && status.afk;
    }

    /**
     * Loads the player's state from Redis.
     *
     * <p>Use this when you explicitly want a Redis fallback, such as
     * immediately after Velocity starts.
     */
    public AfkStatus getFromRedis(UUID uuid) {
        JedisPool pool = this.jedisPool;

        if (pool == null || pool.isClosed()) {
            return null;
        }

        try (Jedis jedis = pool.getResource()) {
            String json = jedis.hget(HASH_KEY, uuid.toString());

            if (json == null) {
                return null;
            }

            AfkStatus incoming = parse(json);

            if (incoming != null) {
                cache.merge(uuid, incoming, PlayerStatusService::preferNewer);
            }

            return incoming;
        } catch (Exception e) {
            logger.debug("Failed to read AFK status for {}: {}", uuid, e.getMessage());
            return null;
        }
    }

    public Map<UUID, AfkStatus> snapshot() {
        return Map.copyOf(cache);
    }

    private void ingest(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            UUID uuid = UUID.fromString(obj.get("uuid").getAsString());

            AfkStatus incoming = parse(obj);

            if (incoming == null) {
                return;
            }

            cache.merge(uuid, incoming, PlayerStatusService::preferNewer);

        } catch (Exception e) {
            logger.debug("Failed to ingest player status update: {}", e.getMessage());
        }
    }

    private AfkStatus parse(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            return parse(obj);
        } catch (Exception e) {
            logger.debug("Invalid player status payload: {}", e.getMessage());
            return null;
        }
    }

    private AfkStatus parse(JsonObject obj) {
        if (!obj.has("afk")) {
            return null;
        }

        boolean afk = obj.get("afk").getAsBoolean();

        long changedAt = obj.has("changed_at") ? obj.get("changed_at").getAsLong() : 0L;

        return new AfkStatus(afk, changedAt);
    }

    public record AfkStatus(boolean afk, long changedAt) {

    }

    private final class SubscriberThread extends Thread {

        volatile boolean cancelled = false;
        volatile JedisPubSub subscriber;

        @Override
        public void run() {
            long backoffMs = 1000L;
            boolean warned = false;

            while (!cancelled) {
                JedisPool pool = jedisPool;

                if (pool == null || pool.isClosed()) {
                    return;
                }

                try (Jedis jedis = pool.getResource()) {

                    if (warned) {
                        logger.info("Player status Redis subscription reconnected.");
                        warned = false;
                    }

                    subscriber = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            if (UPDATE_CHANNEL.equals(channel)) {
                                ingest(message);
                            }
                        }
                    };

                    backoffMs = 1000L;

                    jedis.subscribe(subscriber, UPDATE_CHANNEL);

                } catch (Exception e) {
                    if (cancelled) {
                        return;
                    }

                    if (!warned) {
                        logger.warn("Player status Redis subscription " + "unavailable; retrying: {}", e.getMessage());
                        warned = true;
                    } else {
                        logger.debug("Player status Redis subscription " + "dropped: {}", e.getMessage());
                    }

                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ignored) {
                        return;
                    }

                    backoffMs = Math.min(30_000L, backoffMs * 2L);
                }
            }
        }
    }
}