package crabcraft.net.crabUtilities;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class StatsRedisHandler {

    private static final String REQUEST_CHANNEL = "crabutilities:stats-request";
    private static final String RESPONSE_CHANNEL = "crabutilities:stats-response";
    private static final String SEPARATOR = "\0";

    private final CrabUtilities plugin;
    private final String host;
    private final int port;
    private final String password;
    private JedisPool jedisPool;
    private Thread subscriberThread;
    private JedisPubSub pubSub;

    public StatsRedisHandler(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);

        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            plugin.getLogger().info("StatsRedisHandler connected to Redis at " + host + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("StatsRedisHandler failed to connect to Redis: " + e.getMessage());
            return;
        }

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                int sep = message.indexOf(SEPARATOR);
                if (sep == -1) return;
                String requestId = message.substring(0, sep);
                String uuid = message.substring(sep + 1);

                File statsFile = new File(
                        plugin.getServer().getWorlds().get(0).getWorldFolder(),
                        "stats/" + uuid + ".json"
                );

                String response;
                if (statsFile.exists()) {
                    try {
                        response = requestId + SEPARATOR + Files.readString(statsFile.toPath());
                    } catch (IOException e) {
                        plugin.getLogger().warning("Failed to read stats file for " + uuid + ": " + e.getMessage());
                        response = requestId + SEPARATOR;
                    }
                } else {
                    response = requestId + SEPARATOR;
                }

                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.publish(RESPONSE_CHANNEL, response);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to publish stats response: " + e.getMessage());
                }
            }
        };

        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(pubSub, REQUEST_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    plugin.getLogger().warning("Stats Redis subscriber disconnected, reconnecting in 3s...");
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-Stats-Redis-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
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
