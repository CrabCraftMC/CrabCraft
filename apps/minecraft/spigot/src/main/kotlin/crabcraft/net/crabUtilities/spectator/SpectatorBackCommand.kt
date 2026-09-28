package crabcraft.net.crabUtilities.spectator

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

class SpectatorBackCommand : CommandExecutor, Listener {
    private val startingLocations = ConcurrentHashMap<UUID, Location>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (event.newGameMode == GameMode.SPECTATOR)
            startingLocations[event.player.uniqueId] = event.player.location.clone()
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        startingLocations.remove(event.player.uniqueId)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Only players can use /specback.", NamedTextColor.RED))
            return true
        }
        if (args.isNotEmpty()) {
            sender.sendMessage(
                Component.text("Usage: ", NamedTextColor.RED).append(Component.text("/specback", NamedTextColor.GOLD))
            )
            return true
        }
        if (sender.gameMode != GameMode.SPECTATOR) {
            sender.sendMessage(Component.text("You must be in spectator mode to use /specback.", NamedTextColor.RED))
            return true
        }
        val startingLocation = startingLocations[sender.uniqueId]
        if (startingLocation == null) {
            sender.sendMessage(Component.text("No spectator starting location is recorded.", NamedTextColor.RED))
            return true
        }
        sender.teleportAsync(startingLocation).whenComplete { success, error ->
            if (sender.isOnline) {
                if (error != null || success != true)
                    sender.sendMessage(
                        Component.text(
                            "Could not return you to your spectator starting location.",
                            NamedTextColor.RED,
                        )
                    )
                else
                    sender.sendMessage(
                        Component.text("Returned to your spectator starting location.", NamedTextColor.GREEN)
                    )
            }
        }
        return true
    }
}
