package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ConnectionListener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexCharacter('#')
            .hexColors()
            .build();

    private final CrabUtilitiesVelocity plugin;

    public ConnectionListener(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
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

        boolean firstJoin = plugin.getJoinedPlayersStore().isNew(player.getUniqueId());

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

        if (firstJoin) {
            plugin.getJoinedPlayersStore().markJoined(player.getUniqueId());
        }

        // Update player info in PostgreSQL
        final String playerUuid = player.getUniqueId().toString();
        final String playerName = player.getUsername();
        CompletableFuture.runAsync(() -> {
            String plain = plugin.getNicknameCache().getPlainNickname(player.getUniqueId());
            String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
            plugin.getPgWriter().upsertPlayer(playerUuid, playerName, plain, raw);
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

    private boolean isIgnored(String serverName) {
        return plugin.getConfig().getIgnoredServers().contains(serverName.toLowerCase());
    }

    private void broadcast(Component message) {
        for (Player p : plugin.getServer().getAllPlayers()) {
            p.sendMessage(message);
        }
    }
}
