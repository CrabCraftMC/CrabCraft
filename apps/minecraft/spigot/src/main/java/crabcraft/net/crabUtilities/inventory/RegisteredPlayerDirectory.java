package crabcraft.net.crabUtilities.inventory;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps command lookups local while including registered players who are offline. */
public final class RegisteredPlayerDirectory {

    public static final String HASH_KEY = "crabutilities:registered-players";

    public record Entry(UUID uuid, String name) {}

    private final CrabUtilities plugin;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile List<Entry> entries = List.of();
    private volatile boolean stopped;
    private JedisPool jedisPool;
    private BukkitTask refreshTask;
    private boolean failureLogged;

    public RegisteredPlayerDirectory(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public void start() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxWait(Duration.ofMillis(1500));
        String host = plugin.getConfig().getString("redis.host", "localhost");
        int port = plugin.getConfig().getInt("redis.port", 6379);
        String password = plugin.getConfig().getString("redis.password", "");
        jedisPool = password == null || password.isEmpty()
                ? new JedisPool(poolConfig, host, port, 2000)
                : new JedisPool(poolConfig, host, port, 2000, password);
        refreshTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::refresh, 0L, 20L * 60L);
    }

    public Optional<Entry> find(String name) {
        return find(entries, name);
    }

    public List<String> names(String prefix) {
        return names(entries, prefix);
    }

    private void refresh() {
        if (stopped || !refreshing.compareAndSet(false, true)) return;
        try (Jedis jedis = jedisPool.getResource()) {
            List<Entry> refreshed = parseEntries(jedis.hgetAll(HASH_KEY));
            if (!stopped) entries = refreshed;
            if (failureLogged) {
                plugin.getLogger().info("Registered player directory recovered.");
                failureLogged = false;
            }
        } catch (Exception e) {
            if (!stopped && !failureLogged) {
                plugin.getLogger().warning("Failed to refresh registered player directory; will retry in 60 seconds: "
                        + e.getMessage());
                failureLogged = true;
            }
        } finally {
            refreshing.set(false);
        }
    }

    static List<Entry> parseEntries(Map<String, String> registered) {
        List<Entry> parsed = new ArrayList<>();
        registered.forEach((uuid, name) -> {
            if (uuid == null || name == null || name.isBlank()) return;
            try {
                parsed.add(new Entry(UUID.fromString(uuid), name));
            } catch (IllegalArgumentException ignored) {
                // An invalid cache row must not hide the remaining registered accounts.
            }
        });
        parsed.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::uuid));
        return List.copyOf(parsed);
    }

    static Optional<Entry> find(List<Entry> entries, String name) {
        if (name == null) return Optional.empty();
        Entry found = null;
        for (Entry entry : entries) {
            if (!entry.name().equalsIgnoreCase(name)) continue;
            // Stored names can become stale after a rename; never select an ambiguous account.
            if (found != null) return Optional.empty();
            found = entry;
        }
        return Optional.ofNullable(found);
    }

    static List<String> names(List<Entry> entries, String prefix) {
        String lowerPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Entry entry : entries) {
            if (entry.name().toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) names.add(entry.name());
        }
        return List.copyOf(names);
    }

    public void shutdown() {
        stopped = true;
        if (refreshTask != null) refreshTask.cancel();
        if (jedisPool != null && !jedisPool.isClosed()) jedisPool.close();
        entries = List.of();
    }
}
