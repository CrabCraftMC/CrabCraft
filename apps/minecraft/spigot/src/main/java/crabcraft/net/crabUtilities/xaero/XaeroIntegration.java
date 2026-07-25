package crabcraft.net.crabUtilities.xaero;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Supplies a stable backend identity to Xaero map clients.
 */
public final class XaeroIntegration implements Listener {

    static final String MINIMAP_CHANNEL = "xaerominimap:main";
    static final String WORLDMAP_CHANNEL = "xaeroworldmap:main";
    static final List<String> CHANNELS = List.of(MINIMAP_CHANNEL, WORLDMAP_CHANNEL);
    static final List<Long> JOIN_SEND_DELAYS_TICKS = List.of(0L, 20L, 40L);

    private final JavaPlugin plugin;
    private final byte[] serverIdPayload;

    public XaeroIntegration(@NotNull JavaPlugin plugin, int serverId) {
        this.plugin = plugin;
        this.serverIdPayload = encodeServerId(serverId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player joinedPlayer = event.getPlayer();
        UUID playerId = joinedPlayer.getUniqueId();
        UUID expectedWorldId = joinedPlayer.getWorld().getUID();

        for (long delay : JOIN_SEND_DELAYS_TICKS) {
            if (delay == 0L) {
                sendWorldInfo(joinedPlayer);
                continue;
            }
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> retryJoinSend(playerId, joinedPlayer, expectedWorldId), delay);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRegisterChannel(@NotNull PlayerRegisterChannelEvent event) {
        if (isXaeroChannel(event.getChannel())) {
            sendOnChannel(event.getPlayer(), event.getChannel());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        sendWorldInfo(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPostRespawn(@NotNull PlayerPostRespawnEvent event) {
        sendWorldInfo(event.getPlayer());
    }

    private void retryJoinSend(@NotNull UUID playerId, @NotNull Player joinedPlayer,
                               @NotNull UUID expectedWorldId) {
        Player currentPlayer = plugin.getServer().getPlayer(playerId);
        if (!canRetryJoinSend(currentPlayer, joinedPlayer, expectedWorldId)) return;
        sendWorldInfo(currentPlayer);
    }

    static boolean canRetryJoinSend(@Nullable Player currentPlayer, @NotNull Player joinedPlayer,
                                    @NotNull UUID expectedWorldId) {
        return currentPlayer == joinedPlayer
                && currentPlayer.isOnline()
                && currentPlayer.getWorld().getUID().equals(expectedWorldId);
    }

    static boolean isXaeroChannel(@NotNull String channel) {
        return CHANNELS.contains(channel);
    }

    static byte @NotNull [] encodeServerId(int serverId) {
        return new byte[]{
            0,
            (byte) (serverId >>> 24),
            (byte) (serverId >>> 16),
            (byte) (serverId >>> 8),
            (byte) serverId
        };
    }

    private void sendWorldInfo(@NotNull Player player) {
        for (String channel : CHANNELS) {
            if (player.getListeningPluginChannels().contains(channel)) {
                sendOnChannel(player, channel);
            }
        }
    }

    private void sendOnChannel(@NotNull Player player, @NotNull String channel) {
        player.sendPluginMessage(plugin, channel, Arrays.copyOf(serverIdPayload, serverIdPayload.length));
    }
}
