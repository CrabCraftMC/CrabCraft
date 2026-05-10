package crabcraft.net.crabUtilities.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mirror of the Spigot-side StatsPushTask. Walks the world's stats/
 * and advancements/ folders on a schedule and publishes any changed
 * files to Redis on channel {@code crabutilities:stats-push}, which
 * the Velocity proxy already subscribes to.
 *
 * <p>Payload:
 * <pre>{"season":"&lt;id&gt;","uuid":"&lt;uuid&gt;","stats":{...},"advancements":{...}}</pre>
 */
public final class StatsPushTask {

    private static final Logger LOGGER = CrabUtilitiesNeoForge.LOGGER;
    private static final String CHANNEL = "crabutilities:stats-push";

    private final MinecraftServer server;
    private final Map<String, Long> lastSeenMtime = new HashMap<>();

    private JedisPool jedisPool;
    private ScheduledExecutorService scheduler;
    private String season;

    public StatsPushTask(MinecraftServer server) {
        this.server = server;
    }

    public void start() {
        this.season = CrabConfig.SEASON.get().trim();
        if (season.isEmpty()) {
            LOGGER.info("[CrabUtilities] Stats push DISABLED: 'season' is not set in config");
            return;
        }

        String host = CrabConfig.REDIS_HOST.get();
        int port = CrabConfig.REDIS_PORT.get();
        String password = CrabConfig.REDIS_PASSWORD.get();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port);
        }

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            LOGGER.info("[CrabUtilities] StatsPushTask connected to Redis at {}:{}", host, port);
        } catch (Exception e) {
            LOGGER.error("[CrabUtilities] StatsPushTask failed to connect to Redis: {}", e.getMessage());
            jedisPool.close();
            jedisPool = null;
            return;
        }

        long intervalMinutes = Math.max(1L, CrabConfig.STATS_INTERVAL_MINUTES.get());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crabutilities-stats-push");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
                this::scanSafe, 0L, intervalMinutes, TimeUnit.MINUTES);
        LOGGER.info(
                "[CrabUtilities] Stats push scheduled every {} minute(s) on channel {}",
                intervalMinutes, CHANNEL);
    }

    private void scanSafe() {
        try {
            scan();
        } catch (Throwable t) {
            // Don't let exceptions kill the scheduler.
            LOGGER.warn("[CrabUtilities] Stats push scan failed: {}", t.getMessage());
        }
    }

    private void scan() {
        if (jedisPool == null) return;

        // Resolve the active world directory and use its stats/ + advancements/
        // subfolders. Vanilla writes both there at runtime.
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path statsDir = worldRoot.resolve("stats");
        Path advDir = worldRoot.resolve("advancements");

        if (!Files.isDirectory(statsDir)) return;

        int pushed = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(statsDir, "*.json")) {
            for (Path file : entries) {
                String name = file.getFileName().toString();
                long mtime;
                try {
                    mtime = Files.getLastModifiedTime(file).toMillis();
                } catch (IOException e) {
                    continue;
                }
                Long prev = lastSeenMtime.get(name);
                if (prev != null && prev == mtime) continue;

                String uuid = name.substring(0, name.length() - ".json".length());
                String raw;
                try {
                    raw = Files.readString(file);
                } catch (IOException e) {
                    LOGGER.warn("[CrabUtilities] Failed to read stats file {}: {}", name, e.getMessage());
                    continue;
                }

                JsonObject statsJson;
                try {
                    statsJson = JsonParser.parseString(raw).getAsJsonObject();
                } catch (Exception e) {
                    LOGGER.warn("[CrabUtilities] Skipping malformed stats file {}", name);
                    continue;
                }

                JsonObject envelope = new JsonObject();
                envelope.addProperty("season", season);
                envelope.addProperty("uuid", uuid);
                envelope.add("stats", statsJson);

                Path advFile = advDir.resolve(uuid + ".json");
                if (Files.isRegularFile(advFile)) {
                    try {
                        String advRaw = Files.readString(advFile);
                        JsonObject advJson = JsonParser.parseString(advRaw).getAsJsonObject();
                        // Strip recipe unlocks — they bloat the payload and
                        // aren't real advancements (matches Spigot side).
                        advJson.keySet().removeIf(k -> k.startsWith("minecraft:recipes/"));
                        envelope.add("advancements", advJson);
                    } catch (Exception e) {
                        // Non-fatal; just omit advancements.
                    }
                }

                String payload = envelope.toString();
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.publish(CHANNEL, payload);
                    lastSeenMtime.put(name, mtime);
                    pushed++;
                } catch (Exception e) {
                    LOGGER.warn("[CrabUtilities] Failed to publish stats for {}: {}", uuid, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[CrabUtilities] Failed to list stats dir: {}", e.getMessage());
            return;
        }

        if (pushed > 0) {
            LOGGER.info("[CrabUtilities] Pushed {} stats update(s).", pushed);
        }
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (NoClassDefFoundError ignored) {
                // Defensive: classloader teardown can race with shutdown.
            }
            jedisPool = null;
        }
    }
}
