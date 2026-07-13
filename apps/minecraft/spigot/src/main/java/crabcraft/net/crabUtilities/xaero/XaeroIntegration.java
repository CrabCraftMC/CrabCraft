package crabcraft.net.crabUtilities.xaero;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors Xaero's server-side {@code PlayerList.sendLevelInfo} lifecycle so
 * each current client world receives the same configured id. Join packets are
 * retried because Xaero clients can miss the initial packet during connection.
 */
public final class XaeroIntegration implements Listener {

    static final List<Long> JOIN_SEND_DELAYS_TICKS = List.of(0L, 20L, 40L);

    private final JavaPlugin plugin;

    public XaeroIntegration(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
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

    private static void sendWorldInfo(@NotNull Player player) {
        XaeroMapProtocol.onSendWorldInfo(((CraftPlayer) player).getHandle());
    }
}
