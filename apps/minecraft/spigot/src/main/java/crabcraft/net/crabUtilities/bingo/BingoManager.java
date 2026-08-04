package crabcraft.net.crabUtilities.bingo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Tracks the active card and atomically publishes newly completed tasks to Redis. */
public final class BingoManager {

    private static final String DEFAULT_STREAM = "crabcraft:bingo:completions";
    private static final String DEFAULT_ACTIVE_CARD_KEY = "crabcraft:bingo:active-card";
    private static final int MAX_PENDING_COMPLETIONS = 10_000;
    private static final String RECORD_COMPLETION_SCRIPT = """
            if redis.call('SADD', KEYS[1], ARGV[1]) == 0 then return 0 end
            redis.call('EXPIREAT', KEYS[1], ARGV[2])
            redis.call('XADD', KEYS[2], 'MAXLEN', '~', 100000, '*',
                'card_id', ARGV[3],
                'minecraft_uuid', ARGV[4],
                'task_id', ARGV[1],
                'completed_at', ARGV[5],
                'source_backend', ARGV[6])
            return 1
            """;

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final ConcurrentLinkedQueue<PendingCompletion> pending = new ConcurrentLinkedQueue<>();
    private final Set<String> excludedWorlds;
    private final String stream;
    private final String activeCardKey;
    private final String sourceBackend;
    private final boolean enabled;
    private JedisPool jedisPool;
    private HardBingoListener listener;
    private volatile BingoActiveCard activeCard;
    private volatile boolean redisFailureLogged;

    public BingoManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("bingo.enabled", false);
        this.stream = plugin.getConfig().getString("bingo.redis-stream", DEFAULT_STREAM);
        this.activeCardKey = plugin.getConfig().getString("bingo.active-card-key", DEFAULT_ACTIVE_CARD_KEY);
        String configuredBackend = plugin.getConfig().getString("bingo.source-backend", "");
        this.sourceBackend = configuredBackend == null || configuredBackend.isBlank()
                ? "unknown" : configuredBackend;
        this.excludedWorlds = Set.copyOf(plugin.getConfig().getStringList("bingo.excluded-worlds"));
    }

    public void start() {
        if (!enabled) return;
        String host = plugin.getConfig().getString("redis.host", "localhost");
        int port = plugin.getConfig().getInt("redis.port", 6379);
        String password = plugin.getConfig().getString("redis.password", "");
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        jedisPool = password == null || password.isEmpty()
                ? new JedisPool(poolConfig, host, port, 2_000)
                : new JedisPool(poolConfig, host, port, 2_000, password);
        listener = new HardBingoListener(
                plugin, this::isTracking, this::complete, this::logHornProgress);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshActiveCard, 0L, 20L * 30L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushPending, 20L, 20L);
        plugin.getLogger().info("Bingo tracking enabled; waiting for the active card from Redis.");
    }

    public void shutdown() {
        flushPending();
        if (listener != null) {
            listener.clear();
            org.bukkit.event.HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
        activeCard = null;
    }

    void complete(Player player, BingoTask task) {
        BingoActiveCard card = activeCard;
        if (!isEligible(player) || card == null || !card.isLive() || !card.contains(task)) return;
        if (pending.size() >= MAX_PENDING_COMPLETIONS) {
            plugin.getLogger().severe("Bingo completion queue is full; rejecting " + player.getUniqueId());
            return;
        }
        pending.add(new PendingCompletion(
                card,
                player.getUniqueId(),
                task,
                Instant.now().getEpochSecond()));
    }

    boolean isEligible(Player player) {
        BingoActiveCard card = activeCard;
        return enabled
                && player.getGameMode() == GameMode.SURVIVAL
                && !excludedWorlds.contains(player.getWorld().getName())
                && card != null
                && card.isLive();
    }

    boolean isTracking(Player player, BingoTask task) {
        BingoActiveCard card = activeCard;
        return isEligible(player) && card != null && card.contains(task);
    }

    private void logHornProgress(Player player, HardBingoListener.HornProgress progress) {
        plugin.getLogger().info("Bingo goat horn progress for " + player.getName()
                + ": " + progress.uniqueCount() + "/5 (" + progress.instrument() + ")");
    }

    private void refreshActiveCard() {
        JedisPool pool = jedisPool;
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(activeCardKey);
            BingoActiveCard next = json == null ? null : BingoActiveCard.fromJson(json);
            if (next != null) {
                List<String> unsupported = next.taskIds().stream()
                        .filter(taskId -> BingoTask.fromId(taskId).isEmpty())
                        .toList();
                if (next.taskIds().size() != 16 || !unsupported.isEmpty()) {
                    plugin.getLogger().severe("Refusing Bingo #" + next.number()
                            + ": expected 16 deployed task detectors; unsupported=" + unsupported);
                    next = null;
                }
            }
            BingoActiveCard previous = activeCard;
            activeCard = next;
            if (next != null && (previous == null || previous.id() != next.id())) {
                plugin.getLogger().info("Tracking Bingo #" + next.number() + " with "
                        + next.taskIds().size() + " tasks.");
                if (listener != null) Bukkit.getScheduler().runTask(plugin, listener::clear);
            }
            redisRecovered();
        } catch (Exception e) {
            logRedisFailure("load the active bingo card", e);
        }
    }

    private void flushPending() {
        JedisPool pool = jedisPool;
        if (pool == null) return;
        PendingCompletion completion;
        while ((completion = pending.peek()) != null) {
            try (Jedis jedis = pool.getResource()) {
                String progressKey = "crabcraft:bingo:progress:" + completion.card().id()
                        + ":" + completion.playerId();
                Object result = jedis.eval(
                        RECORD_COMPLETION_SCRIPT,
                        List.of(progressKey, stream),
                        List.of(
                                completion.task().id(),
                                Long.toString(completion.card().endsAt() + 86_400L),
                                Integer.toString(completion.card().id()),
                                completion.playerId().toString(),
                                Long.toString(completion.completedAt()),
                                sourceBackend));
                pending.remove(completion);
                if (Long.valueOf(1L).equals(result)) sendCompletionMessage(completion);
                redisRecovered();
            } catch (Exception e) {
                logRedisFailure("publish bingo completions", e);
                return;
            }
        }
    }

    private void sendCompletionMessage(PendingCompletion completion) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(completion.playerId());
            if (player == null || !player.isOnline()) return;
            if (plugin instanceof crabcraft.net.crabUtilities.CrabUtilities crabUtilities
                    && !crabUtilities.isBingoMessagesEnabled(completion.playerId())) return;
            Component message = miniMessage.deserialize(
                    "<#b0b0b0>Completed</#b0b0b0> "
                            + "<#FCD05C>" + completion.task().description() + "</#FCD05C>")
                    .decoration(TextDecoration.ITALIC, false);
            player.sendMessage(message);
        });
    }

    private void redisRecovered() {
        if (redisFailureLogged) {
            plugin.getLogger().info("Bingo Redis connection recovered.");
            redisFailureLogged = false;
        }
    }

    private void logRedisFailure(String action, Exception error) {
        if (!redisFailureLogged) {
            plugin.getLogger().warning("Unable to " + action + "; retrying without blocking the server: "
                    + error.getMessage());
            redisFailureLogged = true;
        }
    }

    private record PendingCompletion(
            BingoActiveCard card,
            UUID playerId,
            BingoTask task,
            long completedAt) {}
}
