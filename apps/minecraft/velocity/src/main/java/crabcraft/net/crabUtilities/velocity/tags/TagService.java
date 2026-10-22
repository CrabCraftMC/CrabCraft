package crabcraft.net.crabUtilities.velocity.tags;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.scheduler.ScheduledTask;
import crabcraft.net.crabUtilities.tags.TagBridgeProtocol;
import crabcraft.net.crabUtilities.tags.TagBridgeProtocol.Status;
import crabcraft.net.crabUtilities.tags.TagSnapshot;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.RedisPools;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import crabcraft.net.crabUtilities.velocity.db.TagRepository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Proxy authority for committed tag choices. Redis only invalidates online snapshots. */
public final class TagService {
    public static final String INVALIDATE_CHANNEL = "crabcraft:tags:invalidate";
    private static final int REFRESH_BATCH_SIZE = 32;

    private final CrabUtilitiesVelocity plugin;
    private final TagRepository repository;
    private final VelocityConfig config;
    private final ConcurrentHashMap<UUID, TagSession> sessions = new ConcurrentHashMap<>();
    private final ArrayDeque<Player> refreshBatch = new ArrayDeque<>();
    private final VelocityTagBridge bridge;
    private volatile boolean stopped;
    private ScheduledTask refreshTask;
    private ScheduledTask batchTask;
    private volatile JedisPool jedisPool;
    private volatile JedisPubSub subscriber;
    private Thread subscriberThread;

    public TagService(CrabUtilitiesVelocity plugin, TagRepository repository, VelocityConfig config) {
        this.plugin = plugin;
        this.repository = repository;
        this.config = config;
        this.bridge = new VelocityTagBridge(plugin, this);
    }

    public void start() {
        bridge.start();
        plugin.getServer().getEventManager().register(plugin, this);
        if (repository == null) return;
        this.jedisPool = RedisPools.create(config, 1);
        startSubscriber();
        refreshTask = plugin.getServer().getScheduler().buildTask(plugin, this::refreshAll)
                .repeat(Duration.ofSeconds(30)).schedule();
        batchTask = plugin.getServer().getScheduler().buildTask(plugin, this::refreshBatch)
                .repeat(Duration.ofSeconds(1)).schedule();
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (stopped) return;
        TagSession session = session(player);
        if (session != null) refresh(session);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        sessions.computeIfPresent(player.getUniqueId(), (id, session) ->
                session.player == player ? null : session);
    }

    void request(Player player, ServerConnection source, TagBridgeProtocol.Packet packet) {
        TagSession session = session(player);
        if (session == null) return;
        Runnable unavailable = () -> reply(session, source, packet.requestId(), Status.UNAVAILABLE, null);
        if (!current(session, source) || repository == null) {
            unavailable.run();
            return;
        }
        boolean selection = packet.type() == TagBridgeProtocol.Type.SELECT;
        synchronized (session) {
            if (selection && session.mutationPending) {
                unavailable.run();
                return;
            }
            if (selection) session.mutationPending = true;
        }
        Runnable rejected = () -> {
            if (selection) synchronized (session) { session.mutationPending = false; }
            unavailable.run();
        };
        enqueue(session, () -> {
            try {
                if (!current(session, source)) return;
                if (selection) {
                    var result = repository.select(player.getUniqueId(), packet.selectedTagId(),
                            packet.expectedRevision());
                    TagSnapshot snapshot = accept(session, result.snapshot());
                    if (snapshot != null) {
                        reply(session, source, packet.requestId(), result.status(), snapshot);
                        push(session, snapshot);
                    }
                } else {
                    TagSnapshot snapshot = accept(session, repository.load(player.getUniqueId()));
                    if (snapshot != null) reply(session, source, packet.requestId(), Status.OK, snapshot);
                }
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warn("Tag request failed for {}: {}", player.getUniqueId(), e.getMessage());
                unavailable.run();
            } finally {
                if (selection) synchronized (session) { session.mutationPending = false; }
            }
        }, rejected);
    }

    public void inspect(UUID playerId, Consumer<TagSnapshot> completed, Runnable unavailable) {
        if (stopped || repository == null) {
            unavailable.run();
            return;
        }
        boolean accepted = plugin.runDatabaseTask("tags-inspect", () -> {
            if (stopped) { unavailable.run(); return; }
            try {
                TagSnapshot snapshot = repository.load(playerId);
                if (!stopped) completed.accept(snapshot);
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warn("Tag inspection failed for {}: {}", playerId, e.getMessage());
                unavailable.run();
            }
        });
        if (!accepted) unavailable.run();
    }

    public void mutate(UUID playerId, String tagId, boolean grant, String actor, String reason,
                       Consumer<TagRepository.MutationResult> completed, Runnable unavailable) {
        if (stopped || repository == null) {
            unavailable.run();
            return;
        }
        boolean accepted = plugin.runDatabaseTask("tags-" + (grant ? "grant" : "revoke"), () -> {
            if (stopped) { unavailable.run(); return; }
            try {
                var result = grant
                        ? repository.grant(playerId, tagId, "manual", actor, reason)
                        : repository.revoke(playerId, tagId, actor, reason);
                plugin.getLogger().info("Tag {} actor={} target={} tag={} changed={} status={} reason={}",
                        grant ? "grant" : "revoke", actor, playerId, tagId, result.changed(), result.status(), reason);
                if (stopped) return;
                TagSession session = sessions.get(playerId);
                if (session != null) {
                    TagSnapshot snapshot = accept(session, result.snapshot());
                    if (snapshot != null) push(session, snapshot);
                }
                completed.accept(result);
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warn("Tag {} failed actor={} target={} tag={} reason={}: {}",
                        grant ? "grant" : "revoke", actor, playerId, tagId, reason, e.getMessage());
                unavailable.run();
            }
        });
        if (!accepted) unavailable.run();
    }

    public synchronized void shutdown() {
        if (stopped) return;
        stopped = true;
        bridge.shutdown();
        plugin.getServer().getEventManager().unregisterListener(plugin, this);
        if (refreshTask != null) refreshTask.cancel();
        if (batchTask != null) batchTask.cancel();
        if (subscriber != null) {
            try { subscriber.unsubscribe(); } catch (Exception ignored) {}
        }
        if (subscriberThread != null) subscriberThread.interrupt();
        JedisPool pool = jedisPool;
        jedisPool = null;
        if (pool != null) pool.close();
        sessions.clear();
        synchronized (refreshBatch) { refreshBatch.clear(); }
    }

    private TagSession session(Player player) {
        TagSession result = sessions.compute(player.getUniqueId(), (id, existing) -> {
            if (stopped || !player.isActive() || plugin.getServer().getPlayer(id).orElse(null) != player) return existing;
            return existing != null && existing.player == player ? existing : new TagSession(player, id);
        });
        return result != null && result.player == player ? result : null;
    }

    private boolean current(TagSession session) {
        return !stopped && sessions.get(session.playerId) == session && session.player.isActive()
                && plugin.getServer().getPlayer(session.playerId).orElse(null) == session.player;
    }

    private boolean current(TagSession session, ServerConnection source) {
        return current(session) && session.player.getCurrentServer().filter(source::equals).isPresent();
    }

    private TagSnapshot accept(TagSession session, TagSnapshot snapshot) {
        synchronized (session) {
            return current(session) ? session.accept(snapshot) : null;
        }
    }

    private synchronized void reply(TagSession session, ServerConnection source, UUID requestId,
                       Status status, TagSnapshot snapshot) {
        if (current(session, source)) {
            bridge.reply(source, requestId, session.playerId, status, snapshot);
        }
    }

    private synchronized void push(TagSession session, TagSnapshot snapshot) {
        if (current(session)) bridge.push(session.player, snapshot);
    }

    private void enqueue(TagSession session, Runnable action, Runnable rejected) {
        boolean start;
        synchronized (session) {
            if (!current(session) || session.pending.size() >= TagSession.MAX_PENDING) {
                rejected.run();
                return;
            }
            session.pending.add(new TagSession.Work(action, rejected));
            start = !session.running;
            session.running = true;
        }
        if (start && !plugin.runDatabaseTask("tags-player", () -> drain(session))) {
            ArrayList<TagSession.Work> discarded;
            synchronized (session) {
                discarded = new ArrayList<>(session.pending);
                session.pending.clear();
                session.running = false;
            }
            discarded.forEach(work -> work.rejected().run());
        }
    }

    private void drain(TagSession session) {
        while (true) {
            TagSession.Work work;
            synchronized (session) {
                work = session.pending.poll();
                if (work == null) { session.running = false; return; }
            }
            if (current(session)) work.action().run(); else work.rejected().run();
        }
    }

    private void refresh(TagSession session) {
        if (repository == null || !current(session)) return;
        synchronized (session) {
            if (session.refreshing) return;
            session.refreshing = true;
        }
        Runnable finished = () -> { synchronized (session) { session.refreshing = false; } };
        enqueue(session, () -> {
            try {
                TagSnapshot snapshot = accept(session, repository.load(session.playerId));
                if (snapshot != null) push(session, snapshot);
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().debug("Tag refresh failed for {}: {}", session.playerId, e.getMessage());
            } finally {
                finished.run();
            }
        }, finished);
    }

    private void refreshAll() {
        if (stopped) return;
        synchronized (refreshBatch) {
            refreshBatch.clear();
            refreshBatch.addAll(plugin.getServer().getAllPlayers());
        }
    }

    private void refreshBatch() {
        for (int i = 0; i < REFRESH_BATCH_SIZE && !stopped; i++) {
            Player player;
            synchronized (refreshBatch) { player = refreshBatch.poll(); }
            if (player == null) return;
            TagSession session = session(player);
            if (session != null) refresh(session);
        }
    }

    private void startSubscriber() {
        subscriberThread = new Thread(() -> {
            boolean warned = false;
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    subscriber = new JedisPubSub() {
                        @Override
                        public void onSubscribe(String channel, int count) { refreshAll(); }

                        @Override
                        public void onMessage(String channel, String message) {
                            if (!INVALIDATE_CHANNEL.equals(channel) || stopped) return;
                            try {
                                UUID playerId = UUID.fromString(message);
                                if (!playerId.toString().equals(message)) return;
                                plugin.getServer().getPlayer(playerId).filter(Player::isActive)
                                        .ifPresent(player -> {
                                            TagSession session = session(player);
                                            if (session != null) refresh(session);
                                        });
                            } catch (IllegalArgumentException ignored) {
                                // Invalidations contain canonical UUIDs only; no authority is carried here.
                            }
                        }
                    };
                    jedis.subscribe(subscriber, INVALIDATE_CHANNEL);
                    warned = false;
                } catch (Exception e) {
                    if (stopped || Thread.currentThread().isInterrupted()) break;
                    if (!warned) {
                        plugin.getLogger().warn("Tag Redis invalidations unavailable; periodic refresh remains active: {}",
                                e.getMessage());
                        warned = true;
                    }
                    try { Thread.sleep(3000L); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
        }, "CrabUtilities-Tags");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }
}
