package crabcraft.net.crabUtilities.velocity.litebans;

import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import litebans.api.Entry;
import litebans.api.Events;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.XAddParams;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches active LiteBans ban/mute state and publishes state-change events to a
 * Redis Stream for the Discord bot. The bot still performs periodic REST
 * reconciliation, so missed stream messages or watcher downtime only delay sync.
 */
public final class PunishmentEventPublisher {

    private static final long STREAM_MAX_LEN = 10_000L;

    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private final String streamName;
    private JedisPool jedisPool;
    private ScheduledExecutorService executor;
    private Events.Listener liteBansListener;
    private Set<String> lastActiveUuids;
    private volatile boolean redisFailureLogged;
    private volatile boolean liteBansFailureLogged;
    private volatile boolean liteBansEventFailureLogged;

    public PunishmentEventPublisher(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.streamName = config.getRedisPunishmentStream();
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        if (config.getRedisPassword() != null && !config.getRedisPassword().isEmpty()) {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000, config.getRedisPassword());
        } else {
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(),
                    config.getRedisPort(), 2000);
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "crabutilities-punishment-events");
            thread.setDaemon(true);
            return thread;
        });
        registerLiteBansListener();
        executor.scheduleWithFixedDelay(() -> pollAndPublish("watch"),
                0L,
                config.getRedisPunishmentWatchIntervalSeconds(),
                TimeUnit.SECONDS);
        plugin.getLogger().info("Punishment event publisher started on Redis stream {} (LiteBans events + {}s fallback watch interval).",
                streamName, config.getRedisPunishmentWatchIntervalSeconds());
    }

    public void shutdown() {
        unregisterLiteBansListener();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
        lastActiveUuids = null;
    }

    private void registerLiteBansListener() {
        try {
            liteBansListener = new Events.Listener() {
                @Override
                public void entryAdded(Entry entry) {
                    handleEntryAdded(entry);
                }

                @Override
                public void entryRemoved(Entry entry) {
                    handleEntryRemoved(entry);
                }
            };
            Events.get().register(liteBansListener);
            plugin.getLogger().info("Punishment event publisher registered LiteBans event listener.");
            liteBansEventFailureLogged = false;
        } catch (Throwable e) {
            if (!liteBansEventFailureLogged) {
                plugin.getLogger().warn("Punishment event publisher could not register LiteBans events; fallback watch interval remains active.", e);
                liteBansEventFailureLogged = true;
            }
            liteBansListener = null;
        }
    }

    private void unregisterLiteBansListener() {
        Events.Listener listener = liteBansListener;
        if (listener == null) return;

        try {
            Events.get().unregister(listener);
        } catch (Throwable e) {
            plugin.getLogger().debug("Failed to unregister LiteBans punishment event listener: {}", e.getMessage());
        } finally {
            liteBansListener = null;
        }
    }

    private void handleEntryAdded(Entry entry) {
        String type = getEntryType(entry);
        if (type == null) {
            triggerDiff("litebans-event");
            return;
        }
        if (!isTrackedPunishmentType(type)) return;

        String uuid = normalizeEntryUuid(entry.getUuid());
        if (uuid == null || !entry.isActive()) {
            triggerDiff("litebans-event");
            return;
        }

        ScheduledExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown()) return;

        currentExecutor.execute(() -> {
            if (publish(uuid, true, "litebans-event") && lastActiveUuids != null) {
                lastActiveUuids.add(uuid);
            }
        });
    }

    private void handleEntryRemoved(Entry entry) {
        String type = getEntryType(entry);
        if (type == null || isTrackedPunishmentType(type)) {
            triggerDiff("litebans-event");
        }
    }

    private void triggerDiff(String source) {
        ScheduledExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown()) return;

        currentExecutor.execute(() -> pollAndPublish(source));
    }

    private void pollAndPublish(String source) {
        LiteBansInfractionService service = plugin.getLiteBansInfractionService();
        if (service == null) return;

        Set<String> current;
        try {
            current = service.getAllActivePunishedUuids();
            if (liteBansFailureLogged) {
                plugin.getLogger().info("Punishment event publisher recovered LiteBans access.");
                liteBansFailureLogged = false;
            }
        } catch (LiteBansInfractionService.LiteBansUnavailableException | SQLException e) {
            if (!liteBansFailureLogged) {
                plugin.getLogger().warn("Punishment event publisher cannot query LiteBans; REST reconcile will cover gaps.", e);
                liteBansFailureLogged = true;
            } else {
                plugin.getLogger().debug("Punishment event publisher LiteBans query failed: {}", e.getMessage());
            }
            return;
        }

        Set<String> previous = lastActiveUuids;
        Set<String> next = new LinkedHashSet<>(current);
        boolean publishedAllDeltas = true;

        if (previous == null) {
            for (String uuid : current) {
                publishedAllDeltas &= publish(uuid, true, "snapshot");
            }
            if (publishedAllDeltas) {
                lastActiveUuids = next;
            }
            return;
        }

        for (String uuid : current) {
            if (!previous.contains(uuid)) {
                publishedAllDeltas &= publish(uuid, true, source);
            }
        }
        for (String uuid : previous) {
            if (!current.contains(uuid)) {
                publishedAllDeltas &= publish(uuid, false, source);
            }
        }

        if (publishedAllDeltas) {
            lastActiveUuids = next;
        }
    }

    private boolean publish(String uuid, boolean active, String source) {
        JedisPool pool = jedisPool;
        if (pool == null) return false;

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("uuid", uuid);
        fields.put("active", Boolean.toString(active));
        fields.put("source", source);
        fields.put("occurred_at", Long.toString(Instant.now().getEpochSecond()));

        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(streamName,
                    XAddParams.xAddParams().maxLen(STREAM_MAX_LEN).approximateTrimming(),
                    fields);
            if (redisFailureLogged) {
                plugin.getLogger().info("Punishment event Redis publisher recovered.");
                redisFailureLogged = false;
            }
            return true;
        } catch (Exception e) {
            if (!redisFailureLogged) {
                plugin.getLogger().warn("Failed to publish punishment event to Redis; REST reconcile will cover gaps.", e);
                redisFailureLogged = true;
            } else {
                plugin.getLogger().debug("Failed to publish punishment event for {}: {}", uuid, e.getMessage());
            }
            return false;
        }
    }

    private static String getEntryType(Entry entry) {
        if (entry == null || entry.getType() == null) return null;
        return entry.getType().toLowerCase(Locale.ROOT);
    }

    private static boolean isTrackedPunishmentType(String type) {
        return "ban".equals(type) || "bans".equals(type)
                || "mute".equals(type) || "mutes".equals(type);
    }

    private static String normalizeEntryUuid(String rawUuid) {
        if (rawUuid == null || rawUuid.isBlank()) return null;
        String trimmed = rawUuid.trim();
        try {
            if (trimmed.length() == 32) {
                trimmed = trimmed.substring(0, 8) + "-"
                        + trimmed.substring(8, 12) + "-"
                        + trimmed.substring(12, 16) + "-"
                        + trimmed.substring(16, 20) + "-"
                        + trimmed.substring(20);
            }
            return UUID.fromString(trimmed).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
