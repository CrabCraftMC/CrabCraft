package crabcraft.net.bingotest;

import org.bukkit.GameMode;
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
        // This standalone harness is only launched on a loopback-bound test server.
        // Granting operator here keeps setup username-independent and must never be
        // copied into the production CrabUtilities plugin.
        event.getPlayer().setOp(true);
        event.getPlayer().setGameMode(GameMode.CREATIVE);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            event.getPlayer().setGameMode(GameMode.CREATIVE);
            manager.sendChecklist(event.getPlayer());
        }, 20L);
    }
}
