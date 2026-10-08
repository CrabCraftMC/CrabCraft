package crabcraft.net.crabUtilities;

import com.google.gson.JsonObject;
import net.ess3.api.IUser;
import net.ess3.api.events.AfkStatusChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerStatusPublisher implements Listener {

    public static final String HASH_KEY = "crabutilities:player-status";
    public static final String UPDATE_CHANNEL = "crabutilities:player-status-updates";

    private final CrabUtilities plugin;

    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;

    private JedisPool jedisPool;
    private ExecutorService executor;

    public PlayerStatusPublisher(CrabUtilities plugin) {
        this.plugin = plugin;

        this.redisHost = plugin.getConfig().getString("redis.host", "localhost");
        this.redisPort = plugin.getConfig().getInt("redis.port", 6379);
        this.redisPassword = plugin.getConfig().getString("redis.password", "");
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxIdle(2);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            this.jedisPool = new JedisPool(
                    poolConfig,
                    redisHost,
                    redisPort,
                    2000,
                    redisPassword
            );
        } else {
            this.jedisPool = new JedisPool(
                    poolConfig,
                    redisHost,
                    redisPort,
                    2000
            );
        }

        this.executor = Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "crabutilities-player-status"
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );

        plugin.getServer()
                .getPluginManager()
                .registerEvents(this, plugin);

        plugin.getLogger().info(
                "Player status publisher started."
        );
    }

    public void shutdown() {
        ExecutorService executor = this.executor;
        this.executor = null;

        if (executor != null) {
            executor.shutdownNow();
        }

        JedisPool pool = this.jedisPool;
        this.jedisPool = null;

        if (pool != null && !pool.isClosed()) {
            try {
                pool.close();
            } catch (NoClassDefFoundError ignored) {
            }
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onAfkStatusChange(AfkStatusChangeEvent event) {
        IUser user = event.getAffected();

        UUID uuid = user.getUUID();
        boolean afk = event.getValue();

        long changedAt = System.currentTimeMillis();

        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid.toString());
        json.addProperty("afk", afk);
        json.addProperty("changed_at", changedAt);

        publishAsync(uuid, json.toString());
    }

    private void publishAsync(UUID uuid, String json) {
        ExecutorService executor = this.executor;

        if (executor == null) {
            return;
        }

        executor.execute(() -> publish(uuid, json));
    }

    private void publish(UUID uuid, String json) {
        JedisPool pool = this.jedisPool;

        if (pool == null || pool.isClosed()) {
            return;
        }

        try (Jedis jedis = pool.getResource()) {
            jedis.hset(
                    HASH_KEY,
                    uuid.toString(),
                    json
            );

            jedis.publish(
                    UPDATE_CHANNEL,
                    json
            );
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "Failed to publish AFK status for "
                            + uuid + ": " + e.getMessage()
            );
        }
    }
}