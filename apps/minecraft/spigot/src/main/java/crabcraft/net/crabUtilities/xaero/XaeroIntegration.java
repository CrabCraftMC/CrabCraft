package crabcraft.net.crabUtilities.xaero;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Mirrors Xaero's server-side {@code PlayerList.sendLevelInfo} lifecycle so
 * each current client world receives the same configured id.
 */
public final class XaeroIntegration implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        sendWorldInfo(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        sendWorldInfo(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPostRespawn(@NotNull PlayerPostRespawnEvent event) {
        sendWorldInfo(event.getPlayer());
    }

    private static void sendWorldInfo(@NotNull Player player) {
        XaeroMapProtocol.onSendWorldInfo(((CraftPlayer) player).getHandle());
    }
}
