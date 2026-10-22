package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class NicknameListener {

    public static final String HASH_KEY = "crabutilities:nicknames";
    public static final String UPDATE_CHANNEL = "crabutilities:nicknames-updates";
    private static final String PUBLISH_UPDATE_SCRIPT = """
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('PUBLISH', ARGV[3], cjson.encode({uuid = ARGV[1], raw = ARGV[2]}))
            """;
    private final CrabUtilitiesVelocity plugin;
    private final VelocityConfig config;
    private JedisPool jedisPool;
    private volatile boolean redisFailureLogged;
    private final Map<UUID, String> pendingUpdates = new ConcurrentHashMap<>();

    public NicknameListener(CrabUtilitiesVelocity plugin, VelocityConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() {
        jedisPool = RedisPools.create(config, 2);
    }

    /** Saves a command change before making it visible or mirroring it to backends. */
    public synchronized boolean changeNickname(UUID uuid, String raw) {
        String nickname = raw == null ? "" : raw;
        String plain = nickname.isEmpty() ? null : NicknameComponentParser.plain(nickname);
        if (!plugin.getPgWriter().updateNickname(
                uuid.toString(), plain, nickname.isEmpty() ? null : nickname)) return false;
        plugin.getNicknameCache().setNickname(uuid, nickname);
        publishNickname(uuid, nickname, plugin.getNicknameCache().snapshot(uuid).version());
        return true;
    }

    /** PostgreSQL owns nickname changes; replace an older Redis mirror after loading it. */
    public synchronized void publishNickname(UUID uuid, String raw, long expectedVersion) {
        if (!plugin.getNicknameCache().isVersion(uuid, expectedVersion)) return;
        String nickname = raw == null ? "" : raw;
        pendingUpdates.put(uuid, nickname);
        plugin.getServer().getScheduler().buildTask(plugin,
                () -> flushStoredNickname(uuid, nickname)).schedule();
    }

    private synchronized void flushStoredNickname(UUID uuid, String nickname) {
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed() || !nickname.equals(pendingUpdates.get(uuid))) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(PUBLISH_UPDATE_SCRIPT, List.of(HASH_KEY),
                    List.of(uuid.toString(), nickname, UPDATE_CHANNEL));
            pendingUpdates.remove(uuid, nickname);
            if (redisFailureLogged) {
                plugin.getLogger().info("Nickname Redis mirror recovered.");
                redisFailureLogged = false;
            }
        } catch (Exception e) {
            if (!redisFailureLogged) {
                plugin.getLogger().warn("Nickname mirror unavailable; saved changes will be retried", e);
                redisFailureLogged = true;
            }
            plugin.getServer().getScheduler().buildTask(plugin,
                    () -> flushStoredNickname(uuid, nickname)).delay(3, TimeUnit.SECONDS).schedule();
        }
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
        if (jedisPool != null && !jedisPool.isClosed()) {
            try {
                jedisPool.close();
            } catch (NoClassDefFoundError ignored) {}
        }
        jedisPool = null;
        pendingUpdates.clear();
    }
}
