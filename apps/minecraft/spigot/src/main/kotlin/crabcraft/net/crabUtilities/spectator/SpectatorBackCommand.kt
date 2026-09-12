package crabcraft.net.crabUtilities.spectator

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Returns spectators to the location where they entered spectator mode. */
class SpectatorBackCommand : CommandExecutor, Listener {
    private val startingLocations = ConcurrentHashMap<UUID, Location>()
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (event.getNewGameMode() == GameMode.SPECTATOR) startingLocations[event.getPlayer().getUniqueId()] = event.getPlayer().getLocation().clone()
    }
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) { startingLocations.remove(event.getPlayer().getUniqueId()) }
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val player = sender as? Player
        if (player == null) { sender.sendMessage(Component.text("Only players can use /specback.", NamedTextColor.RED)); return true }
        if (args.isNotEmpty()) { player.sendMessage(Component.text("Usage: ", NamedTextColor.RED).append(Component.text("/specback", NamedTextColor.GOLD))); return true }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.sendMessage(Component.text("You must be in spectator mode to use /specback.", NamedTextColor.RED)); return true
        }
        val startingLocation = startingLocations[player.getUniqueId()]
        if (startingLocation == null) {
            player.sendMessage(Component.text("No spectator starting location is recorded.", NamedTextColor.RED)); return true
        }
        player.teleportAsync(startingLocation).whenComplete { success, error ->
            if (!player.isOnline) return@whenComplete
            if (error != null || success != true) {
                player.sendMessage(Component.text("Could not return you to your spectator starting location.", NamedTextColor.RED))
                return@whenComplete
            }
            player.sendMessage(Component.text("Returned to your spectator starting location.", NamedTextColor.GREEN))
        }
        return true
    }
}
