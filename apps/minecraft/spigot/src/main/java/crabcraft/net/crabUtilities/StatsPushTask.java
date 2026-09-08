package crabcraft.net.crabUtilities;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import crabcraft.net.crabUtilities.awards.EatingAwardTracker;
import crabcraft.net.crabUtilities.awards.XpLevelReader;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically scans the Minecraft level's saved player data and publishes
 * changed snapshots to Redis so the Velocity proxy can compute award scores
 * and persist them.
 *
 * <p>Snapshots online XP levels on the server thread, then scans saved data
 * asynchronously every {@code stats-push.interval-minutes} (config, default 5)
 * plus once on plugin enable. Skips unchanged players, so steady-state traffic
 * is proportional to active players, not total player count.
 *
 * <p>Payload on channel {@code crabutilities:stats-push}:
 * <pre>{
 *   "season":"6",
 *   "uuid":"&lt;uuid&gt;",
 *   "stats":&lt;raw contents&gt;,
 *   "custom":{"xp_level":42,"eat_veggie":0}
 * }</pre>
 * The {@code custom} object is optional. XP level comes from Paper for online
 * players and falls back to the latest complete player-data save for offline
 * players.
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
    private final File playerDataDir;

    private JedisPool jedisPool;
    private BukkitTask task;
    private final Map<String, Long> lastSeenMtime = new HashMap<>();
    private final Map<String, Long> lastSeenPlayerDataMtime = new HashMap<>();
    private final Map<String, Integer> lastSeenXpLevel = new HashMap<>();
    private final Map<String, Map<String, Long>> lastSeenEatingScores = new HashMap<>();
    private final AtomicBoolean scanRunning = new AtomicBoolean();
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
        this.playerDataDir = playersDirectory.resolve("playerdata").toFile();
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
        this.task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::startScan, 0L, intervalTicks);
        plugin.getLogger().info(
                "Stats push scheduled every " + intervalMinutes + " minute(s) on channel " + CHANNEL
                        + "; Redis will be retried asynchronously if unavailable.");
    }

    private void startScan() {
        if (!scanRunning.compareAndSet(false, true)) return;

        Map<String, Integer> onlineXpLevels = new HashMap<>();
        Map<String, Map<String, Long>> onlineEatingScores = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineXpLevels.put(player.getUniqueId().toString(), player.getLevel());
            try {
                onlineEatingScores.put(player.getUniqueId().toString(), EatingAwardTracker.scores(player));
            } catch (RuntimeException e) {
                onlineEatingScores.put(player.getUniqueId().toString(), Map.of());
                plugin.getLogger().warning("Could not read eating progress for " + player.getUniqueId() + ": " + e.getMessage());
            }
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                scan(Map.copyOf(onlineXpLevels), Map.copyOf(onlineEatingScores));
            } finally {
                scanRunning.set(false);
            }
        });
    }

    /**
     * Iterates the level's {@code players/stats/<uuid>.json} files and pushes
     * anything whose mtime has moved since the previous scan.
     */
    private void scan(Map<String, Integer> onlineXpLevels, Map<String, Map<String, Long>> onlineEatingScores) {
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
                String uuid = file.getName().substring(0, file.getName().length() - ".json".length());
                File playerDataFile = new File(playerDataDir, uuid + ".dat");
                long mtime = file.lastModified();
                long playerDataMtime = playerDataFile.isFile() ? playerDataFile.lastModified() : 0L;
                Long prev = lastSeenMtime.get(file.getName());
                Long previousPlayerDataMtime = lastSeenPlayerDataMtime.get(uuid);
                Integer liveXpLevel = onlineXpLevels.get(uuid);
                Map<String, Long> liveEatingScores = onlineEatingScores.get(uuid);
                if (prev != null && prev == mtime
                        && previousPlayerDataMtime != null
                        && previousPlayerDataMtime == playerDataMtime
                        && !hasLiveXpLevelChanged(liveXpLevel, lastSeenXpLevel.get(uuid))
                        && !hasLiveEatingScoresChanged(liveEatingScores, lastSeenEatingScores.get(uuid))) {
                    continue;
                }

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

                OptionalInt xpLevel = resolveXpLevel(liveXpLevel, playerDataFile.toPath());
                JsonObject custom = new JsonObject();
                if (xpLevel.isPresent()) {
                    custom.addProperty("xp_level", xpLevel.getAsInt());
                } else if (playerDataFile.isFile()) {
                    plugin.getLogger().fine("Could not read XP level for " + uuid);
                }
                Map<String, Long> eatingScores = liveEatingScores != null
                        ? liveEatingScores
                        : EatingAwardTracker.scores(playerDataFile.toPath(), envelope.getAsJsonObject("stats"));
                eatingScores.forEach(custom::addProperty);
                if (!custom.isEmpty()) envelope.add("custom", custom);

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
                lastSeenPlayerDataMtime.put(uuid, playerDataMtime);
                xpLevel.ifPresent(level -> lastSeenXpLevel.put(uuid, level));
                lastSeenEatingScores.put(uuid, eatingScores);
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

    static OptionalInt resolveXpLevel(Integer liveXpLevel, Path playerDataFile) {
        return liveXpLevel == null
                ? XpLevelReader.read(playerDataFile)
                : OptionalInt.of(liveXpLevel);
    }

    static boolean hasLiveXpLevelChanged(Integer liveXpLevel, Integer previousXpLevel) {
        return liveXpLevel != null && !liveXpLevel.equals(previousXpLevel);
    }

    static boolean hasLiveEatingScoresChanged(Map<String, Long> liveScore, Map<String, Long> previousScore) {
        return liveScore != null && !liveScore.equals(previousScore);
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
