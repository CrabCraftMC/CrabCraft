package crabcraft.net.crabUtilities.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.UUID;

public class NicknameListener {

    public static final String HASH_KEY = "crabutilities:nicknames";
    public static final String UPDATE_CHANNEL = "crabutilities:nicknames-updates";

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private JedisPool jedisPool;
    private Thread subscriberThread;
    private JedisPubSub pubSub;
    private volatile boolean redisFailureLogged;

    public NicknameListener(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000);
        }

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (UPDATE_CHANNEL.equals(channel)) ingest(message);
            }
        };

        subscriberThread = new Thread(() -> {
            boolean warned = false;
            while (!Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Nickname Redis subscriber reconnected.");
                        warned = false;
                    }
                    jedis.subscribe(pubSub, UPDATE_CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (!warned) {
                        plugin.getLogger().warn("Nickname Redis subscriber unavailable, reconnecting in 3s...", e);
                        warned = true;
                    } else {
                        plugin.getLogger().debug("Nickname Redis subscriber disconnected: {}", e.getMessage());
                    }
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-Nickname-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    public void publishNickname(UUID uuid, String raw) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;

        String nickname = raw == null ? "" : raw;
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("raw", nickname);

        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.hset(HASH_KEY, uuid.toString(), nickname);
                jedis.publish(UPDATE_CHANNEL, payload.toString());
                if (redisFailureLogged) {
                    plugin.getLogger().info("Nickname Redis publisher recovered.");
                    redisFailureLogged = false;
                }
            } catch (Exception e) {
                if (!redisFailureLogged) {
                    plugin.getLogger().warn("Failed to publish nickname for {}; will retry on later updates", uuid, e);
                    redisFailureLogged = true;
                } else {
                    plugin.getLogger().debug("Failed to publish nickname for {}: {}", uuid, e.getMessage());
                }
            }
        }).schedule();
    }

    public String loadRawNickname(UUID uuid) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return null;

        try (Jedis jedis = pool.getResource()) {
            String raw = jedis.hget(HASH_KEY, uuid.toString());
            if (redisFailureLogged) {
                plugin.getLogger().info("Nickname Redis reader recovered.");
                redisFailureLogged = false;
            }
            return raw;
        } catch (Exception e) {
            if (!redisFailureLogged) {
                plugin.getLogger().warn("Failed to read nickname for {} from Redis", uuid, e);
                redisFailureLogged = true;
            } else {
                plugin.getLogger().debug("Failed to read nickname for {} from Redis: {}", uuid, e.getMessage());
            }
            return null;
        }
    }

    private void ingest(String json) {
        final UUID uuid;
        final String raw;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            uuid = UUID.fromString(obj.get("uuid").getAsString());
            raw = obj.has("raw") && !obj.get("raw").isJsonNull()
                    ? obj.get("raw").getAsString()
                    : "";
        } catch (Exception e) {
            plugin.getLogger().warn("Ignoring malformed nickname Redis update", e);
            return;
        }

        if (plugin.getServer().getPlayer(uuid).filter(player -> player.isActive()).isEmpty()) {
            return;
        }

        plugin.getNicknameCache().setNickname(uuid, raw);
        plugin.getPendingJoinManager().complete(uuid);
        persist(uuid);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getPendingJoinManager().remove(uuid);
        plugin.getNicknameCache().remove(uuid);
        if (plugin.getMessageManager() != null) {
            plugin.getMessageManager().clearReplyTargets(uuid);
            plugin.getMessageManager().clearSpy(uuid);
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
            try {
                subscriberThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (NoClassDefFoundError ignored) {}
        }
        jedisPool = null;
    }

    void persist(UUID uuid) {
        final String uuidStr = uuid.toString();
        final String plain = plugin.getNicknameCache().getPlainNickname(uuid);
        final String raw = plugin.getNicknameCache().getRawNickname(uuid);
        plugin.runDatabaseTask("nickname-persist",
                () -> plugin.getPgWriter().updateNickname(uuidStr, plain, raw));
    }
}
