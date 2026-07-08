package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ConnectionListener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexCharacter('#')
            .hexColors()
            .build();
    private static final String ALT_GROUP = "alt";

    private final CrabUtilitiesVelocity plugin;
    private final Set<UUID> announcedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, ActiveStreakSession> activeStreakSessions = new ConcurrentHashMap<>();

    public ConnectionListener(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
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

        // Seed the authoritative nickname from the database into Redis/proxy
        // cache so backends never need to report local EssentialsX state.
        plugin.runDatabaseTask("nickname-seed", () -> seedNickname(player));

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

        RegisteredServer currentServer = player.getCurrentServer()
                .map(conn -> conn.getServer())
                .orElse(null);
        if (currentServer == null) return;

        String currentServerName = currentServer.getServerInfo().getName();

        // Publish cached nicknames immediately; first-join cache misses are
        // published by the DB seed below so a transient miss cannot clear one.
        if (previousServer != null || plugin.getNicknameCache().getRawNickname(player.getUniqueId()) != null) {
            publishNicknameToBackends(player);
        }

        if (previousServer == null) {
            // Player just joined the proxy
            if (isIgnored(currentServerName)) return;

            // Check if nickname is already cached.
            if (plugin.getNicknameCache().getRawNickname(player.getUniqueId()) != null) {
                broadcastJoin(player);
                return;
            }

            // Wait for the DB/Redis seed, with timeout fallback.
            CompletableFuture<Void> pending = plugin.getPendingJoinManager().register(player.getUniqueId());
            plugin.runDatabaseTask("nickname-post-connect-seed", () -> seedNickname(player));
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
        finishLoginStreakSession(player.getUniqueId());

        var settingsService = plugin.getPlayerSettingsService();
        if (settingsService != null) {
            settingsService.onDisconnect(player.getUniqueId());
        }

        if (!announcedPlayers.remove(player.getUniqueId())) return;

        RegisteredServer lastServer = player.getCurrentServer()
                .map(conn -> conn.getServer())
                .orElse(null);

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
        long now = epochSeconds();
        for (var entry : activeStreakSessions.entrySet()) {
            UUID playerId = entry.getKey();
            ActiveStreakSession session = entry.getValue();
            if (!activeStreakSessions.remove(playerId, session)) continue;

            CreditSegment segment = session.close(now);
            if (segment != null) {
                recordLoginStreakPlaytime(session, segment, false);
            }
        }
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

        var streakService = plugin.getLoginStreakService();
        if (streakService == null) return;
        plugin.runDatabaseTask("login-streak-progress-load", () -> {
            var progress = streakService.getQualificationProgress(playerId.toString());
            if (progress == null) return;
            if (!isCurrentStreakSession(session) || !isPlayerActive(playerId)) return;
            scheduleNextQualificationCheck(session, progress);
        });
    }

    private void finishLoginStreakSession(UUID playerId) {
        ActiveStreakSession session = activeStreakSessions.remove(playerId);
        if (session == null) return;

        CreditSegment segment = session.close(epochSeconds());
        if (segment != null) {
            recordLoginStreakPlaytime(session, segment, false);
        }
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
        });
    }

    private Component getDisplayName(Player player) {
        String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
        if (raw != null) {
            return LEGACY_SERIALIZER.deserialize(raw.replace('&', '§'));
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

    private void seedNickname(Player player) {
        UUID id = player.getUniqueId();
        String cached = plugin.getNicknameCache().getRawNickname(id);
        if (cached != null) {
            publishNickname(id, cached);
            plugin.getPendingJoinManager().complete(id);
            return;
        }

        String raw = plugin.getPgWriter().loadRawNickname(id.toString());
        if (raw != null && !raw.isEmpty()) {
            plugin.getNicknameCache().setNickname(id, raw);
        } else {
            plugin.getNicknameCache().remove(id);
            raw = "";
        }
        publishNickname(id, raw);
        plugin.getPendingJoinManager().complete(id);
    }

    private void publishNicknameToBackends(Player player) {
        String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
        publishNickname(player.getUniqueId(), raw == null ? "" : raw);
    }

    private void publishNickname(UUID uuid, String raw) {
        NicknameListener listener = plugin.getNicknameListener();
        if (listener != null) listener.publishNickname(uuid, raw);
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

    private static final class ActiveStreakSession {
        private final UUID playerId;
        private long lastCreditedAt;
        private boolean closed;
        private ScheduledTask qualificationTask;

        private ActiveStreakSession(UUID playerId, long startedAt) {
            this.playerId = playerId;
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
