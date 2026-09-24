package crabcraft.net.crabUtilities.slime

import crabcraft.net.crabUtilities.CrabMessages
import java.util.Locale
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SlimeCommand : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(CrabMessages.error("Only players can use /slime."))
            return true
        }
        if (args.size != 1) {
            sendUsage(sender)
            return true
        }
        when (args[0].lowercase(Locale.ROOT)) {
            "map" -> openMap(sender)
            "chunk" -> checkChunk(sender)
            else -> sendUsage(sender)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase(Locale.ROOT)
        return SUBCOMMANDS.filter { it.startsWith(prefix) }
    }

    companion object {
        private val SUBCOMMANDS = listOf("map", "chunk")

        @JvmStatic
        fun openMap(player: Player) {
            if (!player.hasPermission("crabutilities.slime.map")) {
                sendNoPermission(player)
                return
            }
            SlimeMap.open(player)
        }

        private fun checkChunk(player: Player) {
            if (!player.hasPermission("crabutilities.slime.chunk")) {
                sendNoPermission(player)
                return
            }
            val chunk = player.chunk
            val slimeChunk = chunk.isSlimeChunk
            val result =
                if (slimeChunk) CrabMessages.success("You are standing in a slime chunk ")
                else CrabMessages.error("You are not standing in a slime chunk ")
            player.sendMessage(
                result
                    .append(CrabMessages.highlight("(${chunk.x}, ${chunk.z})"))
                    .append(if (slimeChunk) CrabMessages.success(".") else CrabMessages.error("."))
            )
        }

        private fun sendUsage(player: Player) {
            player.sendMessage(CrabMessages.error("Usage: ").append(CrabMessages.highlight("/slime <map|chunk>")))
        }

        private fun sendNoPermission(player: Player) {
            player.sendMessage(CrabMessages.error("You do not have permission to use that slime command."))
        }
    }
}
