package crabcraft.net.crabUtilities;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ess3.api.events.AfkStatusChangeEvent;
import net.ess3.api.events.NickChangeEvent;
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

import java.util.UUID;

public class NicknameSync implements Listener {

    private static final String HASH_KEY = "crabutilities:nicknames";
    private static final String UPDATE_CHANNEL = "crabutilities:nicknames-updates";

    private final CrabUtilities plugin;
    private JedisPool jedisPool;
    private SubscriberThread subscriberThread;
    private volatile boolean redisFailureLogged;

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

        syncAll();

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

    public void syncAll() {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                refreshOne(online.getUniqueId());
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Delay so EssentialsX has loaded the user before applying the Redis mirror.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            refreshOne(player.getUniqueId());
        }, 20L);
    }

    /**
     * When a player changes their nick via /nick, push it to Velocity
     * so it's cached and persisted to DB immediately.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNickChange(NickChangeEvent event) {
        Player player = event.getAffected().getBase();
        // Delay one tick so EssentialsX has finished updating internally
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            String newNick = event.getValue() != null ? event.getValue() : "";
            publishNickname(player.getUniqueId(), newNick);
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAfkChange(AfkStatusChangeEvent event) {
        Player player = event.getAffected().getBase();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (!(plugin.getEssentials() instanceof Essentials essentials)) return;
            User user = essentials.getUser(player);
            if (user == null || user.getNickname() == null) return;
            refreshComponentDisplayNick(essentials, player, user.getNickname());
        }, 1L);
    }

    private void refreshOne(UUID uuid) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = pool.getResource()) {
                String raw = jedis.hget(HASH_KEY, uuid.toString());
                if (hasAuthoritativeRedisValue(raw)) applyNickname(uuid, raw);
                if (redisFailureLogged) {
                    plugin.getLogger().info("Nickname Redis connection recovered.");
                    redisFailureLogged = false;
                }
            } catch (Exception e) {
                if (!redisFailureLogged) {
                    plugin.getLogger().warning("Nickname Redis unavailable; will retry from subscription: " + e.getMessage());
                    redisFailureLogged = true;
                }
            }
        });
    }

    private void applyNickname(UUID uuid, String authoritative) {
        Runnable task = () -> {
            Player target = plugin.getServer().getPlayer(uuid);
            if (target == null || !target.isOnline()) return;

            if (!(plugin.getEssentials() instanceof Essentials essentials)) return;
            User user = essentials.getUser(target);
            if (user == null) return;

            String localNick = user.getNickname();
            if (localNick == null) localNick = "";
            String raw = authoritative == null ? "" : authoritative;

            if (!raw.isEmpty() && !raw.equals(localNick)) {
                user.setNickname(raw);
                plugin.refreshMentionAutocomplete();
                plugin.getLogger().info("Synced nickname for " + target.getName() + ": " + raw);
            } else if (raw.isEmpty() && !localNick.isEmpty()) {
                user.setNickname(null);
                plugin.refreshMentionAutocomplete();
                plugin.getLogger().info("Cleared nickname for " + target.getName());
            }
            user.setDisplayNick();
            refreshComponentDisplayNick(essentials, target, raw);
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void publishNickname(UUID uuid, String raw) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        String nickname = raw == null ? "" : raw;
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", uuid.toString());
        payload.addProperty("raw", nickname);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.hset(HASH_KEY, uuid.toString(), nickname);
                jedis.publish(UPDATE_CHANNEL, payload.toString());
                if (redisFailureLogged) {
                    plugin.getLogger().info("Nickname Redis publisher recovered.");
                    redisFailureLogged = false;
                }
            } catch (Exception e) {
                if (!redisFailureLogged) {
                    plugin.getLogger().warning("Failed to publish nickname update; will retry on the next change: "
                            + e.getMessage());
                    redisFailureLogged = true;
                }
            }
        });
    }

    static boolean hasAuthoritativeRedisValue(String stored) {
        return stored != null;
    }

    static Component decoratedNickname(Component decorated, String raw) {
        Component nickname = NicknameComponentResolver.fromRawNick(raw);
        if (nickname == null) return decorated;
        return decorated.replaceText(config -> config.matchLiteral(raw).replacement(nickname));
    }

    private static void refreshComponentDisplayNick(Essentials essentials, Player player, String raw) {
        if (raw.isEmpty()) return;

        if (essentials.getSettings().changeDisplayName()) {
            player.displayName(decoratedNickname(player.displayName(), raw));
        }
        Component playerListName = player.playerListName();
        if (playerListName != null && essentials.getSettings().changePlayerListName()) {
            player.playerListName(decoratedNickname(playerListName, raw));
        }
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
