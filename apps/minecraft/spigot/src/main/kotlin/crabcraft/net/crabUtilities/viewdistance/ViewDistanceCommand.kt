package crabcraft.net.crabUtilities.viewdistance

import crabcraft.net.crabUtilities.CrabMessages
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale
import java.util.function.Supplier

/** Administrative controls for the adaptive view-distance manager. */
class ViewDistanceCommand(private val managerSupplier: Supplier<ViewDistanceManager?>) {
    fun handle(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(CrabMessages.error("You don't have permission to manage adaptive view distance."))
            return true
        }
        val subcommand = if (args.size >= 2) args[1].lowercase(Locale.ROOT) else "status"
        return when (subcommand) {
            "status" -> sendStatus(sender, args)
            "set" -> setDistance(sender, args)
            "pause" -> pause(sender, args)
            "resume" -> resume(sender, args)
            else -> { sendUsage(sender); true }
        }
    }

    fun tabComplete(sender: CommandSender, args: Array<String>): List<String> {
        if (!sender.hasPermission(PERMISSION)) return emptyList()
        if (args.size == 2) return filter(listOf("status", "set", "pause", "resume"), args[1])
        if (args.size == 3 && args[1].equals("set", true)) return filter(listOf("view", "simulation"), args[2])
        if (args.size == 3 && args[1].equals("status", true)) return worldNames(args[2])
        if (args.size == 4 && args[1].equals("set", true)) {
            val manager = managerSupplier.get() ?: return emptyList()
            val kind = DistanceKind.parse(args[2]) ?: return emptyList()
            val distances = if (kind == DistanceKind.VIEW)
                integerRange(manager.getMinimumViewDistance(), manager.getMaximumViewDistance())
            else integerRange(manager.getMinimumSimulationDistance(), manager.getMaximumSimulationDistance())
            return filter(distances, args[3])
        }
        if (args.size == 5 && args[1].equals("set", true)) return worldNames(args[4])
        return emptyList()
    }

    private fun sendStatus(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size > 3) {
            sendUsage(sender)
            return true
        }
        val manager = managerSupplier.get()
        sender.sendMessage(CrabMessages.accent("CrabUtilities adaptive view distance"))
        if (manager == null) {
            sender.sendMessage(CrabMessages.label("State", CrabMessages.error("disabled")))
        } else {
            sender.sendMessage(CrabMessages.label("State", if (manager.isPaused()) CrabMessages.warning("paused") else CrabMessages.success("running")))
            sender.sendMessage(CrabMessages.label("Configured simulation range", "${manager.getMinimumSimulationDistance()}–${manager.getMaximumSimulationDistance()}"))
            sender.sendMessage(CrabMessages.label("Configured view range", "${manager.getMinimumViewDistance()}–${manager.getMaximumViewDistance()}"))
        }
        val worlds = if (args.size == 3) {
            val world = Bukkit.getWorld(args[2])
            if (world == null) {
                sender.sendMessage(CrabMessages.error("Unknown world: " + args[2]))
                return true
            }
            listOf(world)
        } else Bukkit.getWorlds()
        for (world in worlds) {
            val distances = CrabMessages.text("view " + world.getViewDistance()).append(CrabMessages.muted(", "))
                .append(CrabMessages.text("simulation " + world.getSimulationDistance()))
            sender.sendMessage(CrabMessages.label(world.getName(), distances))
        }
        return true
    }

    private fun setDistance(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size < 4 || args.size > 5) {
            sendUsage(sender)
            return true
        }
        val manager = requireManager(sender) ?: return true
        val kind = DistanceKind.parse(args[2])
        if (kind == null) {
            sender.sendMessage(CrabMessages.error("Distance type must be view or simulation."))
            return true
        }
        val distance = try {
            Integer.parseInt(args[3])
        } catch (exception: NumberFormatException) {
            sender.sendMessage(CrabMessages.error("Distance must be a whole number."))
            return true
        }
        val minimum = if (kind == DistanceKind.VIEW) manager.getMinimumViewDistance() else manager.getMinimumSimulationDistance()
        val maximum = if (kind == DistanceKind.VIEW) manager.getMaximumViewDistance() else manager.getMaximumSimulationDistance()
        if (distance < minimum || distance > maximum) {
            sender.sendMessage(CrabMessages.error("${kind.displayName} distance must be between $minimum and $maximum."))
            return true
        }
        val world = resolveWorld(sender, if (args.size == 5) args[4] else null) ?: return true
        if (kind == DistanceKind.VIEW) manager.setManualViewDistance(world, distance) else manager.setManualSimulationDistance(world, distance)
        sender.sendMessage(CrabMessages.success("Set ${world.getName()}'s ${kind.displayName} distance to $distance."))
        if (!manager.isPaused()) sender.sendMessage(CrabMessages.warning("Dynamic adjustment is still running and may change this value."))
        return true
    }

    private fun pause(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size != 2) {
            sendUsage(sender)
            return true
        }
        val manager = requireManager(sender) ?: return true
        if (!manager.pause()) {
            sender.sendMessage(CrabMessages.warning("Dynamic view-distance adjustment is already paused."))
            return true
        }
        sender.sendMessage(CrabMessages.success("Paused dynamic view-distance adjustment; current world values will remain fixed."))
        return true
    }

    private fun resume(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size != 2) {
            sendUsage(sender)
            return true
        }
        val manager = requireManager(sender) ?: return true
        if (!manager.resume()) {
            sender.sendMessage(CrabMessages.warning("Dynamic view-distance adjustment is already running."))
            return true
        }
        sender.sendMessage(CrabMessages.success("Resumed dynamic view-distance adjustment."))
        return true
    }

    private fun requireManager(sender: CommandSender): ViewDistanceManager? {
        val manager = managerSupplier.get()
        if (manager == null) sender.sendMessage(CrabMessages.error("Adaptive view distance is disabled or has invalid configuration."))
        return manager
    }

    private enum class DistanceKind(val displayName: String) {
        VIEW("view"), SIMULATION("simulation");
        companion object {
            fun parse(value: String): DistanceKind? = when (value.lowercase(Locale.ROOT)) {
                "view" -> VIEW
                "simulation", "sim" -> SIMULATION
                else -> null
            }
        }
    }

    companion object {
        private const val PERMISSION = "crabutilities.viewdistance.admin"

        private fun resolveWorld(sender: CommandSender, worldName: String?): World? {
            if (worldName != null) {
                val world = Bukkit.getWorld(worldName)
                if (world == null) sender.sendMessage(CrabMessages.error("Unknown world: $worldName"))
                return world
            }
            if (sender is Player) return sender.getWorld()
            sender.sendMessage(CrabMessages.error("Specify a world when running this command from console."))
            return null
        }

        private fun worldNames(prefix: String): List<String> = filter(Bukkit.getWorlds().map { it.getName() }, prefix)
        private fun integerRange(minimum: Int, maximum: Int): List<String> = (minimum..maximum).map { it.toString() }
        private fun filter(values: List<String>, prefix: String): List<String> {
            val normalised = prefix.lowercase(Locale.ROOT)
            return values.filter { it.lowercase(Locale.ROOT).startsWith(normalised) }
        }
        private fun sendUsage(sender: CommandSender) {
            sender.sendMessage(CrabMessages.error("Usage: /crabutilities viewdistance <status [world]|set <view|simulation> <distance> [world]|pause|resume>"))
        }
    }
}
