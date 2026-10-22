package crabcraft.net.crabUtilities;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.UUID;

public class NicknameSync implements Listener {

    private static final String HASH_KEY = "crabutilities:nicknames";
    private static final String UPDATE_CHANNEL = "crabutilities:nicknames-updates";

    private final CrabUtilities plugin;
    private JedisPool jedisPool;
    private SubscriberThread subscriberThread;

    public NicknameSync(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public void start() {
        String redisHost = plugin.getConfig().getString("redis.host", "localhost");
        int redisPort = plugin.getConfig().getInt("redis.port", 6379);
        String redisPassword = plugin.getConfig().getString("redis.password", "");
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(2);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword);
        } else {
            this.jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000);
        }

        subscriberThread = new SubscriberThread();
        subscriberThread.setName("crabutilities-nickname-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        plugin.getLogger().info("Nickname Redis sync started; Redis will be retried asynchronously if unavailable.");
    }

    public void shutdown() {
        if (subscriberThread != null) {
            subscriberThread.cancelled = true;
            try {
                if (subscriberThread.subscriber != null && subscriberThread.subscriber.isSubscribed()) {
                    subscriberThread.subscriber.unsubscribe();
                }
            } catch (Exception ignored) {}
            subscriberThread.interrupt();
            subscriberThread = null;
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        applyDisplayName(event.getPlayer());
    }

    private void syncAll(JedisPool pool) {
        Map<String, String> stored;
        try (Jedis jedis = pool.getResource()) {
            stored = jedis.hgetAll(HASH_KEY);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            stored.forEach((id, raw) -> {
                try {
                    NicknameComponentResolver.cacheNickname(UUID.fromString(id), raw);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().fine("Ignoring invalid nickname UUID: " + id);
                }
            });
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                applyDisplayName(player);
            }
            plugin.refreshMentionAutocomplete();
        });
    }

    private void applyNickname(UUID uuid, String raw) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            NicknameComponentResolver.cacheNickname(uuid, raw);
            Player target = plugin.getServer().getPlayer(uuid);
            if (target == null || !target.isOnline()) return;
            applyDisplayName(target);
            plugin.refreshMentionAutocomplete();
        });
    }

    static void applyDisplayName(Player player) {
        Component nickname = NicknameComponentResolver.forPlayer(player);
        Component displayName = nickname == null ? Component.text(player.getName()) : nickname;
        player.displayName(displayName);
        player.playerListName(displayName);
    }

    private void ingest(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
            String raw = obj.has("raw") && !obj.get("raw").isJsonNull()
                    ? obj.get("raw").getAsString()
                    : "";
            applyNickname(uuid, raw);
        } catch (Exception e) {
            plugin.getLogger().fine("Bad nickname update payload: " + e.getMessage());
        }
    }

    private final class SubscriberThread extends Thread {
        volatile boolean cancelled = false;
        volatile JedisPubSub subscriber;

        @Override
        public void run() {
            long backoffMs = 1000L;
            boolean warned = false;
            while (!cancelled) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) return;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Nickname Redis subscription reconnected.");
                        warned = false;
                    }
                    subscriber = new JedisPubSub() {
                        @Override
                        public void onSubscribe(String channel, int subscribedChannels) {
                            // Load while this subscriber is paused so queued updates follow the snapshot.
                            syncAll(pool);
                        }

                        @Override
                        public void onMessage(String channel, String message) {
                            if (UPDATE_CHANNEL.equals(channel)) ingest(message);
                        }
                    };
                    backoffMs = 1000L;
                    jedis.subscribe(subscriber, UPDATE_CHANNEL);
                } catch (Exception e) {
                    if (cancelled) return;
                    if (!warned) {
                        plugin.getLogger().warning("Nickname Redis subscription unavailable; retrying: "
                                + e.getMessage());
                        warned = true;
                    } else {
                        plugin.getLogger().fine("Nickname subscription dropped: " + e.getMessage());
                    }
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { return; }
                    backoffMs = Math.min(30_000L, backoffMs * 2L);
                }
            }
        }
    }
}
