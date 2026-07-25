package crabcraft.net.crabUtilities.velocity.staffchat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.RedisPools;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

public class RedisStaffChat {

    private static final String SEPARATOR = "\0";
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

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
        jedisPool = RedisPools.create(config, 4);
        plugin.getLogger().info("Staff chat Redis subscriber starting for {}:{}; reconnects will run asynchronously.",
                config.getRedisHost(), config.getRedisPort());

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                StaffMessage staffMessage = decode(message);
                if (staffMessage == null) return;

                plugin.getServer().getScheduler()
                        .buildTask(plugin, () ->
                                plugin.getStaffChatManager().displayMessage(
                                        staffMessage.senderName(), staffMessage.message())
                        )
                        .schedule();
            }
        };

        subscriberThread = new Thread(() -> {
            boolean warned = false;
            while (!Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Staff chat Redis subscriber reconnected.");
                        warned = false;
                    }
                    jedis.subscribe(pubSub, config.getRedisChannel());
                } catch (NoClassDefFoundError e) {
                    // Classloader closed during shutdown, exit silently
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (!warned) {
                        plugin.getLogger().warn("Staff chat Redis subscriber unavailable, reconnecting in 3s...", e);
                        warned = true;
                    } else {
                        plugin.getLogger().debug("Staff chat Redis subscriber disconnected: {}", e.getMessage());
                    }
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

    public void publish(String senderName, Component message) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("sender", senderName);
        envelope.addProperty("message", GSON.serialize(message));
        String payload = envelope.toString();
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(config.getRedisChannel(), payload);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to publish staff chat message to Redis", e);
            }
        }).schedule();
    }

    private StaffMessage decode(String payload) {
        try {
            JsonObject envelope = JsonParser.parseString(payload).getAsJsonObject();
            String senderName = envelope.get("sender").getAsString();
            Component message = GSON.deserialize(envelope.get("message").getAsString());
            return new StaffMessage(senderName, message);
        } catch (Exception ignored) {
            // Accept the old delimiter format during a rolling proxy update.
            int separator = payload.indexOf(SEPARATOR);
            if (separator == -1) return null;
            return new StaffMessage(
                    payload.substring(0, separator),
                    Component.text(payload.substring(separator + 1)));
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
            } catch (NoClassDefFoundError ignored) {
                // Velocity's classloader may restrict loading relocated classes during shutdown
            }
        }
    }

    private record StaffMessage(String senderName, Component message) {}
}
