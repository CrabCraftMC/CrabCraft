package crabcraft.net.crabUtilities;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Periodically scans the Minecraft level's {@code players/stats/} folder and
 * publishes the contents of each changed file to Redis so the Velocity
 * proxy can compute award scores and persist them.
 *
 * <p>Runs on an async Bukkit task every
 * {@code stats-push.interval-minutes} (config, default 5) plus once
 * on plugin enable. Skips files whose {@code mtime} hasn't moved
 * since the previous run, so steady-state traffic is proportional to
 * active players, not total player count.
 *
 * <p>Payload on channel {@code crabutilities:stats-push}:
 * <pre>{"season":"6","uuid":"&lt;uuid&gt;","stats":&lt;raw contents&gt;}</pre>
 */
public class StatsPushTask {

    private static final String CHANNEL = "crabutilities:stats-push";
    private static final long TICKS_PER_MINUTE = 20L * 60L;

    private final CrabUtilities plugin;
    private final String host;
    private final int port;
    private final String password;
    private final String season;
    private final long intervalMinutes;
    private final File statsDir;
    private final File advancementsDir;

    private JedisPool jedisPool;
    private BukkitTask task;
    private final Map<String, Long> lastSeenMtime = new HashMap<>();
    private volatile boolean redisFailureLogged;

    public StatsPushTask(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");
        this.season = plugin.getConfig().getString("season", "").trim();
        this.intervalMinutes = Math.max(1L,
                plugin.getConfig().getLong("stats-push.interval-minutes", 5L));
        Path playersDirectory = playerStorageDirectory(plugin.getServer().getLevelDirectory());
        this.statsDir = playersDirectory.resolve("stats").toFile();
        this.advancementsDir = playersDirectory.resolve("advancements").toFile();
    }

    static Path playerStorageDirectory(Path levelDirectory) {
        return levelDirectory.resolve("players");
    }

    public void start() {
        if (season.isEmpty()) {
            plugin.getLogger().info("Stats push DISABLED: 'season' is not set in config.yml");
            return;
        }
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
        }

        long intervalTicks = TICKS_PER_MINUTE * intervalMinutes;
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::scan, 0L, intervalTicks);
        plugin.getLogger().info(
                "Stats push scheduled every " + intervalMinutes + " minute(s) on channel " + CHANNEL
                        + "; Redis will be retried asynchronously if unavailable.");
    }

    /**
     * Iterates the level's {@code players/stats/<uuid>.json} files and pushes
     * anything whose mtime has moved since the previous scan.
     */
    private void scan() {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        if (!statsDir.isDirectory()) return;

        File[] files = statsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        int pushed = 0;
        try (Jedis jedis = pool.getResource()) {
            if (redisFailureLogged) {
                plugin.getLogger().info("Stats push Redis connection recovered.");
                redisFailureLogged = false;
            }
            for (File file : files) {
                long mtime = file.lastModified();
                Long prev = lastSeenMtime.get(file.getName());
                if (prev != null && prev == mtime) continue;

                String uuid = file.getName().substring(0, file.getName().length() - ".json".length());
                String raw;
                try {
                    raw = Files.readString(file.toPath());
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to read stats file " + file.getName() + ": " + e.getMessage());
                    continue;
                }
                // Parse once so we can embed the inner object directly and
                // reject malformed files before they hit the wire.
                try {
                    JsonParser.parseString(raw);
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping malformed stats file " + file.getName());
                    continue;
                }

                JsonObject envelope = new JsonObject();
                envelope.addProperty("season", season);
                envelope.addProperty("uuid", uuid);
                envelope.add("stats", JsonParser.parseString(raw));

                // Include advancements if available for this player
                File advFile = new File(advancementsDir, uuid + ".json");
                if (advFile.isFile()) {
                    try {
                        String advRaw = Files.readString(advFile.toPath());
                        JsonObject advJson = JsonParser.parseString(advRaw).getAsJsonObject();
                        // Strip recipe unlocks — they bloat the payload and aren't real advancements
                        advJson.keySet().removeIf(k -> k.startsWith("minecraft:recipes/"));
                        envelope.add("advancements", advJson);
                    } catch (Exception e) {
                        // Advancement read failure is non-fatal; just skip it
                        plugin.getLogger().fine("Could not read advancements for " + uuid + ": " + e.getMessage());
                    }
                }

                String payload = envelope.toString();
                jedis.publish(CHANNEL, payload);
                lastSeenMtime.put(file.getName(), mtime);
                pushed++;
            }
        } catch (Exception e) {
            if (!redisFailureLogged) {
                plugin.getLogger().warning("Stats push Redis unavailable; will retry on the next scan: "
                        + e.getMessage());
                redisFailureLogged = true;
            }
            return;
        }

        if (pushed > 0) {
            plugin.getLogger().info("Pushed " + pushed + " stats update(s).");
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }
}
