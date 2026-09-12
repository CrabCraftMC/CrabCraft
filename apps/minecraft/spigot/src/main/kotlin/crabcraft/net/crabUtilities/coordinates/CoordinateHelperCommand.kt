package crabcraft.net.crabUtilities.coordinates

import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.util.Locale

/** Translates portal coordinates between the overworld and nether. */
open class CoordinateHelperCommand : CommandExecutor, TabCompleter {
    private enum class CoordinateDimension {
        OVERWORLD, NETHER;
        fun convertToOtherCoordinate(coordinate: Int): Int =
            if (this == OVERWORLD) Math.floorDiv(coordinate, 8) else coordinate * 8
        companion object {
            fun getPlayerDimension(player: Player): CoordinateDimension =
                if (player.getWorld().getEnvironment() == World.Environment.NETHER) NETHER else OVERWORLD
        }
    }
    override fun onCommand(commandSender: CommandSender, command: Command, s: String, args: Array<String>): Boolean {
        val player = commandSender as? Player
        if (player == null) {
            commandSender.sendMessage(CrabMessages.error("Only players can use /portalcoords."))
            return true
        }
        if (args.isEmpty()) {
            handleForCoordinates(player, player.getLocation().getBlockX(), player.getLocation().getBlockZ(), CoordinateDimension.getPlayerDimension(player))
            return true
        }
        if (args[0].lowercase(Locale.ROOT) == "at") handleAtCommand(player, args) else sendUsage(player)
        return true
    }
    override fun onTabComplete(commandSender: CommandSender, command: Command, s: String, strings: Array<String>): List<String> {
        if (strings.size == 1) return filterStartsWith(strings[0], listOf("at"))
        if (strings.size == 2 && strings[0].equals("at", true)) return filterStartsWith(strings[1], listOf("<x>"))
        if (strings.size == 3 && strings[0].equals("at", true)) return filterStartsWith(strings[2], listOf("<z>"))
        // Offer a two-word completion, with a fallback after a manually typed "in".
        if (strings.size == 4 && strings[0].equals("at", true)) return filterStartsWith(strings[3], listOf("in overworld", "in nether"))
        if (strings.size == 5 && strings[0].equals("at", true) && strings[3].equals("in", true)) return filterStartsWith(strings[4], listOf("overworld", "nether"))
        return emptyList()
    }
    private fun handleAtCommand(player: Player, args: Array<String>) {
        if (args.size != 5 || !args[3].equals("in", true)) { sendUsage(player); return }
        val x: Int
        val z: Int
        try { x = args[1].toInt(); z = args[2].toInt() } catch (e: NumberFormatException) { sendUsage(player); return }
        val dimension = when (args[4].lowercase(Locale.ROOT)) {
            "overworld" -> CoordinateDimension.OVERWORLD
            "nether" -> CoordinateDimension.NETHER
            else -> { sendUsage(player); return }
        }
        handleForCoordinates(player, x, z, dimension)
    }
    private fun filterStartsWith(prefix: String, options: List<String>): List<String> {
        val lowerPrefix = prefix.lowercase(Locale.ROOT)
        return options.filter { it.startsWith(lowerPrefix) }
    }
    private fun handleForCoordinates(player: Player, x: Int, z: Int, dimension: CoordinateDimension) {
        val otherX = dimension.convertToOtherCoordinate(x)
        val otherZ = dimension.convertToOtherCoordinate(z)
        val overworld = Component.text(" in the Overworld ", CrabMessages.SUCCESS)
        val nether = Component.text(" in the Nether ", CrabMessages.ERROR)
        val formattedResponse = Component.text()
            .append(Component.text("$x $z", CrabMessages.HIGHLIGHT))
            .append(if (dimension == CoordinateDimension.OVERWORLD) overworld else nether)
            .append(Component.text("is ", CrabMessages.TEXT))
            .append(Component.text("$otherX $otherZ", CrabMessages.HIGHLIGHT))
            .append(if (dimension == CoordinateDimension.OVERWORLD) nether else overworld)
            .append(Component.text("[Click to copy]").decorate(TextDecoration.ITALIC).color(CrabMessages.HIGHLIGHT)
                .clickEvent(ClickEvent.copyToClipboard("$otherX $otherZ"))).build()
        player.sendMessage(formattedResponse)
    }
    private fun sendUsage(player: Player) {
        player.sendMessage(CrabMessages.error("Usage: /portalcoords at [x] [z] in [overworld|nether]"))
    }
}
