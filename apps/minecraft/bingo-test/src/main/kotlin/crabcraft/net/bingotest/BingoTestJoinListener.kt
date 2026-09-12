package crabcraft.net.bingotest

import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

class BingoTestJoinListener(private val plugin: JavaPlugin, private val manager: BingoTestManager) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        // This standalone harness is only launched on a loopback-bound test server.
        // Granting operator here keeps setup username-independent and must never be
        // copied into the production CrabUtilities plugin.
        event.player.isOp = true
        event.player.gameMode = GameMode.CREATIVE
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            event.player.gameMode = GameMode.CREATIVE
            manager.sendChecklist(event.player)
        }, 20L)
    }
}
