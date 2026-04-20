package crabcraft.net.crabUtilities;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PackJoinListener implements Listener {
    private final CrabUtilities plugin;

    public PackJoinListener(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getResourcePackManager().sendIfOptedIn(event.getPlayer());
    }
}

