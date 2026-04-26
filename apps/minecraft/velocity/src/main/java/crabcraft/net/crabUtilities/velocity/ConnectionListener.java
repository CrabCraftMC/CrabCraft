package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.node.types.InheritanceNode;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ConnectionListener {

    private static final MinecraftChannelIdentifier NICKNAME_CHANNEL =
            MinecraftChannelIdentifier.from("crabutilities:nicknames");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexCharacter('#')
            .hexColors()
            .build();
    private static final String ALT_GROUP = "alt";

    private final CrabUtilitiesVelocity plugin;
    private final Set<UUID> announcedPlayers = ConcurrentHashMap.newKeySet();

    public ConnectionListener(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        LuckPerms luckPerms = plugin.getLuckPerms();
        if (luckPerms == null) return; // LuckPerms not available

        String uuid = player.getUniqueId().toString();
        boolean isAlt = plugin.getAltQueryService().isAlt(uuid);

        if (isAlt) {
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user -> {
                user.data().add(InheritanceNode.builder(ALT_GROUP).build());
            }).exceptionally(e -> {
                plugin.getLogger().error("Failed to assign '{}' group to alt {} ({})",
                        ALT_GROUP, player.getUsername(), uuid, e);
                return null;
            });
            plugin.getLogger().info("Alt account {} ({}) — assigned '{}' group",
                    player.getUsername(), uuid, ALT_GROUP);
        } else {
            // Clean up stale alt group if the alt was removed from the database
            luckPerms.getUserManager().modifyUser(player.getUniqueId(), user -> {
                user.data().remove(InheritanceNode.builder(ALT_GROUP).build());
            }).exceptionally(e -> {
                plugin.getLogger().error("Failed to remove '{}' group from {} ({})",
                        ALT_GROUP, player.getUsername(), uuid, e);
                return null;
            });
        }
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

        // Push the authoritative nickname to the backend server so EssentialsX
        // stays in sync across servers. Runs on every server connect (join + swap).
        pushNicknameToBackend(player, currentServer);

        if (previousServer == null) {
            // Player just joined the proxy
            if (isIgnored(currentServerName)) return;

            // Check if nickname is already cached (rare: plugin message arrived before this event)
            if (plugin.getNicknameCache().getRawNickname(player.getUniqueId()) != null) {
                broadcastJoin(player);
                return;
            }

            // Wait for nickname to arrive from Spigot, with timeout fallback
            CompletableFuture<Void> pending = plugin.getPendingJoinManager().register(player.getUniqueId());
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

    private void broadcastJoin(Player player) {
        if (!player.isActive()) return;

        // Run the DB lookup and per-join writes off-thread so a slow
        // Postgres response (up to the Hikari connectionTimeout) doesn't
        // stall the broadcast. Player.sendMessage and MiniMessage are
        // thread-safe, so we can broadcast directly from the async block.
        final UUID playerId = player.getUniqueId();
        final String playerUuid = playerId.toString();
        final String playerName = player.getUsername();
        CompletableFuture.runAsync(() -> {
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

    private void pushNicknameToBackend(Player player, RegisteredServer server) {
        String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
        if (raw == null) return; // No cached nick yet — Spigot will send one on join

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(raw);
            server.sendPluginMessage(NICKNAME_CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to push nickname to backend for {}", player.getUsername(), e);
        }
    }

    private boolean isIgnored(String serverName) {
        return plugin.getConfig().getIgnoredServers().contains(serverName.toLowerCase());
    }

    private void broadcast(Component message) {
        for (Player p : plugin.getServer().getAllPlayers()) {
            p.sendMessage(message);
        }
    }
}
