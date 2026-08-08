package crabcraft.net.bingotest;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

final class BingoTestJoinListener implements Listener {
    private final JavaPlugin plugin;
    private final BingoTestManager manager;

    BingoTestJoinListener(JavaPlugin plugin, BingoTestManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> manager.sendChecklist(event.getPlayer()), 20L);
    }
}
