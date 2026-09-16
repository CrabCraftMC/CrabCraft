package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Reports the authoritative EssentialsX state of each backend player to Velocity. */
public final class VanishStatusPublisher implements Listener, PluginMessageListener {

    private final CrabUtilities plugin;

    public VanishStatusPublisher(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(
                plugin, VanishBridgeProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, VanishBridgeProtocol.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            publishLater(player);
        }
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, VanishBridgeProtocol.CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(
                plugin, VanishBridgeProtocol.CHANNEL);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        publishLater(event.getPlayer());
    }

    public void publish(Player player) {
        publish(player, plugin.isVanished(player));
    }

    public void publish(Player player, boolean vanished) {
        if (!player.isOnline()) return;
        player.sendPluginMessage(plugin, VanishBridgeProtocol.CHANNEL,
                VanishBridgeProtocol.status(vanished));
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!VanishBridgeProtocol.CHANNEL.equals(channel)) return;

        boolean vanished;
        try {
            vanished = VanishBridgeProtocol.decode(message);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Ignored malformed proxy vanish request: " + e.getMessage());
            return;
        }

        if (!VanishStatus.setVanished(plugin.getEssentials(), carrier, vanished)) {
            plugin.getLogger().warning(
                    "Could not apply proxy-requested vanish state for " + carrier.getName());
            return;
        }
        plugin.onVanishStatusChanged(carrier);
        publish(carrier);
    }

    private void publishLater(Player player) {
        // A one-tick delay lets EssentialsX restore persisted user state first.
        Bukkit.getScheduler().runTask(plugin, () -> publish(player));
        // Retry once after the backend connection and plugin channels have
        // fully settled; duplicate reports are harmless on Velocity.
        Bukkit.getScheduler().runTaskLater(plugin, () -> publish(player), 20L);
    }
}
