package crabcraft.net.crabUtilities.velocity.staffchat;

import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

public class RedisStaffChat {

    private static final String SEPARATOR = "\0";

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private JedisPool jedisPool;
    private Thread subscriberThread;
    private JedisPubSub pubSub;

    public RedisStaffChat(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);

        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort());
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            plugin.getLogger().info("Connected to Redis at {}:{}", config.getRedisHost(), config.getRedisPort());
        } catch (Exception e) {
            plugin.getLogger().error("Failed to connect to Redis at {}:{}",
                    config.getRedisHost(), config.getRedisPort(), e);
            return;
        }

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                int sep = message.indexOf(SEPARATOR);
                if (sep == -1) return;
                String senderName = message.substring(0, sep);
                String chatMessage = message.substring(sep + 1);

                plugin.getServer().getScheduler()
                        .buildTask(plugin, () ->
                                plugin.getStaffChatManager().displayMessage(senderName, chatMessage)
                        )
                        .schedule();
            }
        };

        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(pubSub, config.getRedisChannel());
                } catch (NoClassDefFoundError e) {
                    // Classloader closed during shutdown, exit silently
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    plugin.getLogger().warn("Redis subscriber disconnected, reconnecting in 3s...", e);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-Redis-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void publish(String senderName, String message) {
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(config.getRedisChannel(), senderName + SEPARATOR + message);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to publish staff chat message to Redis", e);
            }
        }).schedule();
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
            } catch (NoClassDefFoundError ignored) {
                // Velocity's classloader may restrict loading relocated classes during shutdown
            }
        }
    }

    public VelocityConfig getConfig() {
        return config;
    }
}
