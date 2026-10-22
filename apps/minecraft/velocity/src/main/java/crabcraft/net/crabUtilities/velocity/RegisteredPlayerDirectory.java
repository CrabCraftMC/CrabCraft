package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.scheduler.ScheduledTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Publishes registered account names for backend command lookups. */
public final class RegisteredPlayerDirectory {

    public static final String HASH_KEY = "crabutilities:registered-players";

    private final CrabUtilitiesVelocity plugin;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private JedisPool jedisPool;
    private ScheduledTask refreshTask;
    private volatile boolean stopped;
    private boolean failureLogged;

    public RegisteredPlayerDirectory(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxWait(Duration.ofMillis(1500));
        jedisPool = RedisPools.create(plugin.getConfig(), poolConfig);
        refreshTask = plugin.getServer().getScheduler()
                .buildTask(plugin, this::refresh)
                .repeat(Duration.ofSeconds(60))
                .schedule();
    }

    private void refresh() {
        if (stopped || !refreshing.compareAndSet(false, true)) return;
        try {
            Map<String, String> players = loadRegisteredPlayers();
            if (stopped) return;
            try (Jedis jedis = jedisPool.getResource(); Transaction transaction = jedis.multi()) {
                // Replace the full snapshot atomically, including removed registrations.
                transaction.del(HASH_KEY);
                if (!players.isEmpty()) transaction.hset(HASH_KEY, players);
                transaction.exec();
            }
            if (failureLogged) {
                plugin.getLogger().info("Registered player directory recovered.");
                failureLogged = false;
            }
        } catch (Exception e) {
            if (!stopped && !failureLogged) {
                plugin.getLogger().warn("Failed to refresh registered player directory; will retry in 60 seconds", e);
                failureLogged = true;
            }
        } finally {
            refreshing.set(false);
        }
    }

    private Map<String, String> loadRegisteredPlayers() throws java.sql.SQLException {
        Map<String, String> players = new HashMap<>();
        try (Connection connection = plugin.getPgWriter().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT minecraft_uuid, minecraft_username FROM players
                     WHERE minecraft_uuid IS NOT NULL AND minecraft_username IS NOT NULL
                     UNION
                     SELECT minecraft_uuid, minecraft_username FROM player_alts
                     """);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                players.put(results.getString("minecraft_uuid"), results.getString("minecraft_username"));
            }
        }
        return players;
    }

    public void shutdown() {
        stopped = true;
        if (refreshTask != null) refreshTask.cancel();
        if (jedisPool != null && !jedisPool.isClosed()) jedisPool.close();
    }
}
