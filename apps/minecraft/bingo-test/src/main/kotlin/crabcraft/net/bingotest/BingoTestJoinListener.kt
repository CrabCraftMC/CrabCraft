package crabcraft.net.bingotest

import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

internal class BingoTestJoinListener(
    private val plugin: JavaPlugin,
    private val manager: BingoTestManager,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        // Only the loopback-bound standalone harness grants operator for username-independent setup.
        // This must never be copied into the production CrabUtilities plugin.
        event.player.setOp(true)
        event.player.gameMode = GameMode.CREATIVE
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                event.player.gameMode = GameMode.CREATIVE
                manager.sendChecklist(event.player)
            },
            20L,
        )
    }
}
