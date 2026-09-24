package crabcraft.net.crabUtilities.coordinates

import crabcraft.net.crabUtilities.CrabMessages
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** Translates portal coordinates between the overworld and nether. */
open class CoordinateHelperCommand : CommandExecutor, TabCompleter {
    private enum class CoordinateDimension {
        OVERWORLD,
        NETHER;

        fun convertToOtherCoordinate(coordinate: Int) =
            if (this == OVERWORLD) Math.floorDiv(coordinate, 8) else coordinate * 8

        companion object {
            fun getPlayerDimension(player: Player) =
                if (player.world.environment == World.Environment.NETHER) NETHER else OVERWORLD
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(CrabMessages.error("Only players can use /portalcoords."))
            return true
        }
        if (args.isEmpty()) {
            handleForCoordinates(
                sender,
                sender.location.blockX,
                sender.location.blockZ,
                CoordinateDimension.getPlayerDimension(sender),
            )
        } else if (args[0].lowercase(Locale.ROOT) == "at") handleAtCommand(sender, args) else sendUsage(sender)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> {
        val options =
            when {
                args.size == 1 -> listOf("at")
                args.size == 2 && args[0].equals("at", true) -> listOf("<x>")
                args.size == 3 && args[0].equals("at", true) -> listOf("<z>")
                args.size == 4 && args[0].equals("at", true) -> listOf("in overworld", "in nether")
                args.size == 5 && args[0].equals("at", true) && args[3].equals("in", true) ->
                    listOf("overworld", "nether")
                else -> return emptyList()
            }
        val prefix = args.last().lowercase(Locale.ROOT)
        return options.filter { it.startsWith(prefix) }
    }

    private fun handleAtCommand(player: Player, args: Array<String>) {
        if (args.size != 5 || !args[3].equals("in", true)) {
            sendUsage(player)
            return
        }
        val x: Int
        val z: Int
        try {
            x = Integer.parseInt(args[1])
            z = Integer.parseInt(args[2])
        } catch (_: NumberFormatException) {
            sendUsage(player)
            return
        }
        val dimension =
            when (args[4].lowercase(Locale.ROOT)) {
                "overworld" -> CoordinateDimension.OVERWORLD
                "nether" -> CoordinateDimension.NETHER
                else -> {
                    sendUsage(player)
                    return
                }
            }
        handleForCoordinates(player, x, z, dimension)
    }

    private fun handleForCoordinates(player: Player, x: Int, z: Int, dimension: CoordinateDimension) {
        val otherX = dimension.convertToOtherCoordinate(x)
        val otherZ = dimension.convertToOtherCoordinate(z)
        val overworld = Component.text(" in the Overworld ", CrabMessages.SUCCESS)
        val nether = Component.text(" in the Nether ", CrabMessages.ERROR)
        player.sendMessage(
            Component.text()
                .append(Component.text("$x $z", CrabMessages.HIGHLIGHT))
                .append(if (dimension == CoordinateDimension.OVERWORLD) overworld else nether)
                .append(Component.text("is ", CrabMessages.TEXT))
                .append(Component.text("$otherX $otherZ", CrabMessages.HIGHLIGHT))
                .append(if (dimension == CoordinateDimension.OVERWORLD) nether else overworld)
                .append(
                    Component.text("[Click to copy]")
                        .decorate(TextDecoration.ITALIC)
                        .color(CrabMessages.HIGHLIGHT)
                        .clickEvent(ClickEvent.copyToClipboard("$otherX $otherZ"))
                )
                .build()
        )
    }

    private fun sendUsage(player: Player) {
        player.sendMessage(CrabMessages.error("Usage: /portalcoords at [x] [z] in [overworld|nether]"))
    }
}
