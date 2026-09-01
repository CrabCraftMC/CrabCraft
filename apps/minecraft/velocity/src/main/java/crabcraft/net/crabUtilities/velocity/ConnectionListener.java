package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ConnectionListener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String ALT_GROUP = "alt";
    private static final int MAX_JADE_HANDSHAKE_SIZE = 256;
    private static final MinecraftChannelIdentifier JADE_CLIENT_HANDSHAKE =
            MinecraftChannelIdentifier.from("jade:client_handshake");
    // The backend needs the pre-translation protocol to encode Jade's embedded registry IDs.
    private static final MinecraftChannelIdentifier CLIENT_PROTOCOL =
            MinecraftChannelIdentifier.from("crabcraft:client_protocol");

    private final CrabUtilitiesVelocity plugin;
    private final Set<UUID> announcedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> nicknameSeeds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, ActiveStreakSession> activeStreakSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, byte[]> jadeHandshakes = new ConcurrentHashMap<>();

    public ConnectionListener(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
        plugin.getServer().getChannelRegistrar().register(JADE_CLIENT_HANDSHAKE, CLIENT_PROTOCOL);
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPluginMessage(PluginMessageEvent event) {
        if (CLIENT_PROTOCOL.equals(event.getIdentifier())) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        if (!JADE_CLIENT_HANDSHAKE.equals(event.getIdentifier())
                || !(event.getSource() instanceof Player player)
                || !(event.getTarget() instanceof ServerConnection connection)) {
            return;
        }

        if (!sendClientProtocol(player, connection)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.forward());
        byte[] data = event.getData();
        if (data.length <= MAX_JADE_HANDSHAKE_SIZE) {
            jadeHandshakes.put(player.getUniqueId(), data);
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();

        // Warm the player's settings (Postgres -> cache + Redis hash) so the
        // backend sees them on join and proxy features (message DND) can read them.
        var settingsService = plugin.getPlayerSettingsService();
        if (settingsService != null) {
            settingsService.onLogin(player.getUniqueId());
        }

        // Seed the authoritative nickname from Redis/database into the proxy
        // cache so backends never need to report local EssentialsX state.
        ensureNicknameSeed(player);

        // Start tracking cumulative online time for today's streak credit.
        // The streak itself is only recorded once the player reaches the
        // configured play-time requirement for the current streak day.
        startLoginStreakSession(player);

        LuckPerms luckPerms = plugin.getLuckPerms();
        if (luckPerms == null) return; // LuckPerms not available

        UUID playerId = player.getUniqueId();
        String username = player.getUsername();
        plugin.runDatabaseTask("alt-status-check", () -> {
            var altQueryService = plugin.getAltQueryService();
            if (altQueryService == null) return;

            boolean isAlt = altQueryService.isAlt(uuid);

            // Alts only ever hold a one-day streak; clamp any streak the
            // account built up before it was registered as an alt.
            if (isAlt) {
                var streakService = plugin.getLoginStreakService();
                if (streakService != null) {
                    streakService.capAltStreak(uuid);
                }
            }

            if (!isPlayerActive(playerId)) return;

            if (isAlt) {
                luckPerms.getUserManager().modifyUser(playerId, user -> {
                    if (!isPlayerActive(playerId)) return;
                    user.data().add(InheritanceNode.builder(ALT_GROUP).build());
                }).whenComplete((ignored, e) -> {
                    if (e != null) {
                        plugin.getLogger().error("Failed to assign '{}' group to alt {} ({})",
                                ALT_GROUP, username, uuid, e);
                    } else if (isPlayerActive(playerId)) {
                        plugin.getLogger().info("Alt account {} ({}) — assigned '{}' group",
                                username, uuid, ALT_GROUP);
                    }
                });
            } else {
                // Clean up stale alt group if the alt was removed from the database
                luckPerms.getUserManager().modifyUser(playerId, user -> {
                    if (!isPlayerActive(playerId)) return;
                    user.data().remove(InheritanceNode.builder(ALT_GROUP).build());
                }).whenComplete((ignored, e) -> {
                    if (e != null) {
                        plugin.getLogger().error("Failed to remove '{}' group from {} ({})",
                                ALT_GROUP, username, uuid, e);
                    }
                });
            }
        });
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer previousServer = event.getPreviousServer();

        ServerConnection currentConnection = player.getCurrentServer().orElse(null);
        if (currentConnection == null) return;
        RegisteredServer currentServer = currentConnection.getServer();

        if (previousServer != null) {
            byte[] handshake = jadeHandshakes.get(player.getUniqueId());
            if (handshake != null) {
                if (sendClientProtocol(player, currentConnection)
                        && !currentConnection.sendPluginMessage(JADE_CLIENT_HANDSHAKE, handshake)) {
                    plugin.getLogger().warn("Could not replay Jade handshake for {} to {}",
                            player.getUsername(), currentServer.getServerInfo().getName());
                }
            }
        }

        String currentServerName = currentServer.getServerInfo().getName();

        // Publish cached nickname state immediately. Unknown state must not be
        // sent as a clear while its Redis/database seed is still in flight.
        if (plugin.getNicknameCache().isLoaded(player.getUniqueId())) {
            publishNicknameToBackends(player);
        } else {
            ensureNicknameSeed(player);
        }

        if (previousServer == null) {
            // Player just joined the proxy
            if (isIgnored(currentServerName)) return;

            // Check if nickname is already cached.
            if (plugin.getNicknameCache().isLoaded(player.getUniqueId())) {
                broadcastJoin(player);
                return;
            }

            // Wait for the DB/Redis seed, with timeout fallback.
            CompletableFuture<Void> pending = plugin.getPendingJoinManager().register(player.getUniqueId());
            ensureNicknameSeed(player);
            pending.orTimeout(2, TimeUnit.SECONDS)
                    .whenComplete((result, throwable) -> {
                        plugin.getServer().getScheduler()
                                .buildTask(plugin, () -> broadcastJoin(player))
                                .schedule();
                    });
        } else {
            // Player swapped servers
            String previousServerName = previousServer.getServerInfo().getName();
            if (isIgnored(currentServerName) || isIgnored(previousServerName)) return;

            plugin.getAnalyticsService().capture(
                    player.getUniqueId(),
                    AnalyticsService.SERVER_SWITCHED,
                    properties(
                            "from_server", previousServerName,
                            "to_server", currentServerName));

            Component displayName = getDisplayName(player);
            Component message = MINI_MESSAGE.deserialize(
                    "<yellow><name> swapped to the <server> server</yellow>",
                    Placeholder.component("name", displayName),
                    Placeholder.unparsed("server", currentServerName)
            );
            broadcast(message);

            String discordMsg = formatDiscord(plugin.getConfig().getDiscordSwapFormat(), player, currentServerName);
            plugin.getDiscordWebhook().send(discordMsg);
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        jadeHandshakes.remove(player.getUniqueId());
        RegisteredServer lastServer = player.getCurrentServer()
                .map(conn -> conn.getServer())
                .orElse(null);
        long sessionDuration = finishLoginStreakSession(player.getUniqueId());
        if (sessionDuration > 0L) {
            plugin.getAnalyticsService().capture(
                    player.getUniqueId(),
                    AnalyticsService.PLAYER_SESSION_ENDED,
                    properties(
                            "duration_seconds", sessionDuration,
                            "last_server", lastServer == null
                                    ? null
                                    : lastServer.getServerInfo().getName()));
        }

        var settingsService = plugin.getPlayerSettingsService();
        if (settingsService != null) {
            settingsService.onDisconnect(player.getUniqueId());
        }

        if (!announcedPlayers.remove(player.getUniqueId())) return;

        if (lastServer != null && isIgnored(lastServer.getServerInfo().getName())) return;

        Component displayName = getDisplayName(player);
        Component message = MINI_MESSAGE.deserialize(
                "<yellow><name> left the game</yellow>",
                Placeholder.component("name", displayName)
        );
        broadcast(message);

        String discordMsg = formatDiscord(plugin.getConfig().getDiscordLeaveFormat(), player, null);
        plugin.getDiscordWebhook().send(discordMsg);
    }

    public void shutdown() {
        jadeHandshakes.clear();
        nicknameSeeds.clear();
        plugin.getServer().getChannelRegistrar().unregister(JADE_CLIENT_HANDSHAKE, CLIENT_PROTOCOL);
        long now = epochSeconds();
        for (var entry : activeStreakSessions.entrySet()) {
            UUID playerId = entry.getKey();
            ActiveStreakSession session = entry.getValue();
            if (!activeStreakSessions.remove(playerId, session)) continue;

            CreditSegment segment = session.close(now);
            if (segment != null) {
                recordLoginStreakPlaytime(session, segment, false);
            }
            plugin.getAnalyticsService().capture(
                    playerId,
                    AnalyticsService.PLAYER_SESSION_ENDED,
                    properties(
                            "duration_seconds", session.durationSeconds(now),
                            "disconnect_reason", "proxy_shutdown"));
        }
    }

    private boolean sendClientProtocol(Player player, ServerConnection connection) {
        boolean sent = connection.sendPluginMessage(
                CLIENT_PROTOCOL,
                encodeClientProtocol(player.getProtocolVersion().getProtocol()));
        if (!sent) {
            plugin.getLogger().warn("Could not send client protocol for {} to {}",
                    player.getUsername(), connection.getServer().getServerInfo().getName());
        }
        return sent;
    }

    static byte[] encodeClientProtocol(int protocol) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(protocol).array();
    }

    private void startLoginStreakSession(Player player) {
        UUID playerId = player.getUniqueId();
        long now = epochSeconds();
        ActiveStreakSession session = new ActiveStreakSession(playerId, now);
        ActiveStreakSession previous = activeStreakSessions.put(playerId, session);
        if (previous != null) {
            CreditSegment previousSegment = previous.close(now);
            if (previousSegment != null) {
                recordLoginStreakPlaytime(previous, previousSegment, false);
            }
        }

        String uuid = playerId.toString();
        plugin.runDatabaseTask("login-streak-progress-load", () -> {
            var streakService = plugin.getLoginStreakService();
            if (streakService == null) return;
            var streakPublisher = plugin.getLoginStreakPublisher();
            if (streakPublisher != null) {
                seedLoginStreakCache(
                        () -> streakService.get(uuid),
                        snapshot -> streakPublisher.publish(
                                uuid, snapshot, streakService.getResetHourUtc()));
            }

            var progress = streakService.getQualificationProgress(uuid);
            if (progress == null) return;
            if (!isCurrentStreakSession(session) || !isPlayerActive(playerId)) return;
            scheduleNextQualificationCheck(session, progress);
        });
    }

    static void seedLoginStreakCache(
            Supplier<crabcraft.net.crabUtilities.velocity.db.LoginStreakService.StreakSnapshot> load,
            Consumer<crabcraft.net.crabUtilities.velocity.db.LoginStreakService.StreakSnapshot> publish) {
        var snapshot = load.get();
        if (snapshot != null) publish.accept(snapshot);
    }

    private long finishLoginStreakSession(UUID playerId) {
        ActiveStreakSession session = activeStreakSessions.remove(playerId);
        if (session == null) return 0L;

        long now = epochSeconds();
        CreditSegment segment = session.close(now);
        if (segment != null) {
            recordLoginStreakPlaytime(session, segment, false);
        }
        return session.durationSeconds(now);
    }

    private void creditActiveLoginStreakSession(ActiveStreakSession session) {
        UUID playerId = session.playerId;
        if (!isCurrentStreakSession(session) || !isPlayerActive(playerId)) return;

        CreditSegment segment = session.takeSegment(epochSeconds());
        if (segment != null) {
            recordLoginStreakPlaytime(session, segment, true);
        } else {
            ScheduledTask retry = plugin.getServer().getScheduler()
                    .buildTask(plugin, () -> creditActiveLoginStreakSession(session))
                    .delay(Duration.ofSeconds(1))
                    .schedule();
            session.setTask(retry);
        }
    }

    private void recordLoginStreakPlaytime(ActiveStreakSession session, CreditSegment segment, boolean reschedule) {
        var streakService = plugin.getLoginStreakService();
        if (streakService == null) return;

        var streakPublisher = plugin.getLoginStreakPublisher();
        UUID playerId = session.playerId;
        String uuid = playerId.toString();
        plugin.runDatabaseTask("login-streak-playtime", () -> {
            var result = streakService.recordPlaytime(uuid, segment.from, segment.to);
            if (result == null) return;
            if (result.streakSnapshot != null && streakPublisher != null) {
                streakPublisher.publish(uuid, result.streakSnapshot, streakService.getResetHourUtc());
            }
            if (result.streakSnapshot != null) {
                plugin.getAnalyticsService().capture(
                        playerId,
                        AnalyticsService.LOGIN_DAY_QUALIFIED,
                        properties(
                                "current_streak", result.streakSnapshot.currentStreak,
                                "longest_streak", result.streakSnapshot.longestStreak,
                                "season", plugin.getStatsQueryService().getCurrentSeason()),
                        String.valueOf(result.streakSnapshot.lastLoginAt));
            }
            if (reschedule && result.progress != null
                    && isCurrentStreakSession(session)
                    && isPlayerActive(playerId)) {
                scheduleNextQualificationCheck(session, result.progress);
            }
        });
    }

    private void scheduleNextQualificationCheck(
            ActiveStreakSession session,
            crabcraft.net.crabUtilities.velocity.db.LoginStreakService.QualificationProgress progress) {
        var streakService = plugin.getLoginStreakService();
        if (streakService == null) return;

        long now = epochSeconds();
        long delaySeconds;
        if (progress.qualified) {
            delaySeconds = streakService.secondsUntilNextStreakDay(now)
                    + streakService.getRequiredPlaySeconds();
        } else {
            delaySeconds = progress.remainingSeconds() - session.secondsSinceLastCredit(now);
        }

        Runnable taskBody = () -> creditActiveLoginStreakSession(session);
        ScheduledTask task = delaySeconds <= 0L
                ? plugin.getServer().getScheduler().buildTask(plugin, taskBody).schedule()
                : plugin.getServer().getScheduler().buildTask(plugin, taskBody)
                        .delay(Duration.ofSeconds(delaySeconds))
                        .schedule();
        session.setTask(task);
    }

    private boolean isCurrentStreakSession(ActiveStreakSession session) {
        return activeStreakSessions.get(session.playerId) == session;
    }

    private static long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private void broadcastJoin(Player player) {
        if (!player.isActive()) return;

        // Run the DB lookup and per-join writes off-thread so a slow
        // Postgres response (up to the Hikari connectionTimeout) doesn't
        // stall the broadcast. Player.sendMessage and MiniMessage are
        // thread-safe, so we can broadcast directly from the async block.
        final UUID playerId = player.getUniqueId();
        final String playerUuid = playerId.toString();
        final String playerName = player.getUsername();
        plugin.runDatabaseTask("join-broadcast", () -> {
            boolean firstJoin = !plugin.getPgWriter().hasJoinedBefore(playerUuid);

            // Player may have disconnected during the lookup — skip the
            // broadcast in that case so we don't announce a join for
            // someone who's no longer here.
            if (!player.isActive()) return;

            // Atomic check-and-add: if a previous in-flight task already
            // announced this UUID (rapid disconnect/reconnect), skip.
            if (!announcedPlayers.add(playerId)) return;

            Component displayName = getDisplayName(player);
            String inGameFormat = firstJoin
                    ? plugin.getConfig().getFirstJoinFormat()
                    : "<yellow><name> joined the game</yellow>";
            Component message = MINI_MESSAGE.deserialize(inGameFormat,
                    Placeholder.component("name", displayName),
                    Placeholder.unparsed("username", player.getUsername())
            );
            broadcast(message);

            String discordFormat = firstJoin
                    ? plugin.getConfig().getDiscordFirstJoinFormat()
                    : plugin.getConfig().getDiscordJoinFormat();
            String discordMsg = formatDiscord(discordFormat, player, null);
            plugin.getDiscordWebhook().send(discordMsg);

            // Update player info in PostgreSQL (also sets last_mc_login_at).
            // Order matters: must run after hasJoinedBefore captured the
            // boolean above, otherwise the player would record their own
            // first login as a prior visit.
            String plain = plugin.getNicknameCache().getPlainNickname(playerId);
            String raw = plugin.getNicknameCache().getRawNickname(playerId);
            plugin.getPgWriter().upsertPlayer(playerUuid, playerName, plain, raw);
            plugin.getPgWriter().upsertAltUsername(playerUuid, playerName);
            plugin.getPgWriter().recordMcLogin(playerUuid);
            String backendServer = player.getCurrentServer()
                    .map(connection -> connection.getServer().getServerInfo().getName())
                    .orElse(null);
            plugin.getAnalyticsService().capture(
                    playerId,
                    AnalyticsService.PLAYER_JOINED,
                    properties(
                            "backend_server", backendServer,
                            "first_join", firstJoin,
                            "season", plugin.getStatsQueryService().getCurrentSeason()));
        });
    }

    private Component getDisplayName(Player player) {
        String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
        if (raw != null) {
            return NicknameComponentParser.parse(raw);
        }
        return Component.text(player.getUsername());
    }

    private String getPlainDisplayName(Player player) {
        String plain = plugin.getNicknameCache().getPlainNickname(player.getUniqueId());
        return plain != null ? plain : player.getUsername();
    }

    private String formatDiscord(String template, Player player, String serverName) {
        String result = template
                .replace("{name}", getPlainDisplayName(player))
                .replace("{username}", player.getUsername());
        if (serverName != null) {
            result = result.replace("{server}", serverName);
        }
        return result;
    }

    private void ensureNicknameSeed(Player player) {
        UUID id = player.getUniqueId();
        if (plugin.getNicknameCache().isLoaded(id)) {
            plugin.getPendingJoinManager().complete(id);
            return;
        }
        if (!nicknameSeeds.add(id)) return;

        boolean queued = plugin.runDatabaseTask("nickname-seed", () -> {
            try {
                seedNickname(player);
            } finally {
                finishNicknameSeed(player);
            }
        });
        if (!queued) {
            nicknameSeeds.remove(id);
            plugin.getPendingJoinManager().complete(id);
        }
    }

    private void finishNicknameSeed(Player seededPlayer) {
        UUID id = seededPlayer.getUniqueId();
        nicknameSeeds.remove(id);

        Player currentPlayer = plugin.getServer().getPlayer(id)
                .filter(Player::isActive)
                .orElse(null);
        if (currentPlayer != null
                && currentPlayer != seededPlayer
                && !plugin.getNicknameCache().isLoaded(id)) {
            ensureNicknameSeed(currentPlayer);
            return;
        }
        plugin.getPendingJoinManager().complete(id);
    }

    private void seedNickname(Player player) {
        if (!player.isActive()) return;

        UUID id = player.getUniqueId();
        NicknameCache cache = plugin.getNicknameCache();
        NicknameCache.Snapshot started = cache.beginLoad(id);
        try {
            if (started.loaded()) {
                publishNickname(id, started);
                return;
            }

            NicknameListener listener = plugin.getNicknameListener();
            if (listener != null) {
                String redisRaw = listener.loadRawNickname(id);
                if (redisRaw != null) {
                    if (player.isActive() && cache.commitIfVersion(id, started.version(), redisRaw)) {
                        listener.persist(id);
                    }
                    return;
                }
            }

            var result = plugin.getPgWriter().loadRawNickname(id.toString());
            if (!player.isActive()) return;
            if (commitNicknameLoad(cache, id, started.version(), result)) {
                publishNicknameToBackends(player);
            }
        } finally {
            if (!player.isActive() && !started.loaded()) {
                cache.discardIfUnloadedVersion(id, started.version());
            }
        }
    }

    private void publishNicknameToBackends(Player player) {
        UUID id = player.getUniqueId();
        NicknameCache.Snapshot snapshot = plugin.getNicknameCache().snapshot(id);
        if (snapshot.loaded()) publishNickname(id, snapshot);
    }

    private void publishNickname(UUID uuid, NicknameCache.Snapshot snapshot) {
        NicknameListener listener = plugin.getNicknameListener();
        if (listener != null) {
            listener.publishNickname(uuid, snapshot.rawNickname(), snapshot.version());
        }
    }

    static boolean commitNicknameLoad(NicknameCache cache, UUID id, long expectedVersion,
                                      crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter.NicknameLoadResult result) {
        return switch (result.status()) {
            case FOUND -> cache.commitIfVersion(id, expectedVersion, result.rawNickname());
            case ABSENT -> cache.commitIfVersion(id, expectedVersion, "");
            case FAILED -> false;
        };
    }

    private boolean isIgnored(String serverName) {
        return plugin.getConfig().getIgnoredServers().contains(serverName.toLowerCase());
    }

    private boolean isPlayerActive(UUID playerId) {
        return plugin.getServer().getPlayer(playerId)
                .map(Player::isActive)
                .orElse(false);
    }

    private void broadcast(Component message) {
        for (Player p : plugin.getServer().getAllPlayers()) {
            p.sendMessage(message);
        }
    }

    private static Map<String, Object> properties(Object... pairs) {
        Map<String, Object> properties = new HashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value != null) properties.put(String.valueOf(pairs[index]), value);
        }
        return properties;
    }

    private static final class ActiveStreakSession {
        private final UUID playerId;
        private final long startedAt;
        private long lastCreditedAt;
        private boolean closed;
        private ScheduledTask qualificationTask;

        private ActiveStreakSession(UUID playerId, long startedAt) {
            this.playerId = playerId;
            this.startedAt = startedAt;
            this.lastCreditedAt = startedAt;
        }

        private synchronized CreditSegment takeSegment(long now) {
            if (closed || now <= lastCreditedAt) return null;
            long from = lastCreditedAt;
            lastCreditedAt = now;
            return new CreditSegment(from, now);
        }

        private synchronized CreditSegment close(long now) {
            if (closed) return null;
            closed = true;
            if (qualificationTask != null) {
                qualificationTask.cancel();
                qualificationTask = null;
            }
            if (now <= lastCreditedAt) return null;
            long from = lastCreditedAt;
            lastCreditedAt = now;
            return new CreditSegment(from, now);
        }

        private synchronized void setTask(ScheduledTask task) {
            if (closed) {
                task.cancel();
                return;
            }
            if (qualificationTask != null) {
                qualificationTask.cancel();
            }
            qualificationTask = task;
        }

        private synchronized long secondsSinceLastCredit(long now) {
            return Math.max(0L, now - lastCreditedAt);
        }

        private long durationSeconds(long now) {
            return Math.max(0L, now - startedAt);
        }
    }

    private static final class CreditSegment {
        private final long from;
        private final long to;

        private CreditSegment(long from, long to) {
            this.from = from;
            this.to = to;
        }
    }
}
