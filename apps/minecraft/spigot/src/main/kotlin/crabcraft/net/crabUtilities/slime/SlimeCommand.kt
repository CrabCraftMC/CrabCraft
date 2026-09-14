package crabcraft.net.crabUtilities.slime

import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale

/** Provides /slime map and /slime chunk. */
class SlimeCommand : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val player = sender as? Player
        if (player == null) { sender.sendMessage(CrabMessages.error("Only players can use /slime.")); return true }
        if (args.size != 1) { sendUsage(player); return true }
        when (args[0].lowercase(Locale.ROOT)) { "map" -> openMap(player); "chunk" -> checkChunk(player); else -> sendUsage(player) }
        return true
    }
    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase(Locale.ROOT)
        return SUBCOMMANDS.filter { it.startsWith(prefix) }
    }
    companion object {
        private const val MAP_PERMISSION = "crabutilities.slime.map"
        private const val CHUNK_PERMISSION = "crabutilities.slime.chunk"
        private val SUBCOMMANDS = listOf("map", "chunk")
        @JvmStatic
        fun openMap(player: Player) {
            if (!player.hasPermission(MAP_PERMISSION)) { sendNoPermission(player); return }
            SlimeMap.open(player)
        }
        private fun checkChunk(player: Player) {
            if (!player.hasPermission(CHUNK_PERMISSION)) { sendNoPermission(player); return }
            val chunk = player.getChunk()
            val slimeChunk = chunk.isSlimeChunk
            val message = if (slimeChunk) "You are standing in a slime chunk " else "You are not standing in a slime chunk "
            val result = if (slimeChunk) CrabMessages.success(message) else CrabMessages.error(message)
            player.sendMessage(result.append(CrabMessages.highlight("(${chunk.getX()}, ${chunk.getZ()})"))
                .append(if (slimeChunk) CrabMessages.success(".") else CrabMessages.error(".")))
        }
        private fun sendUsage(player: Player) { player.sendMessage(CrabMessages.error("Usage: ").append(CrabMessages.highlight("/slime <map|chunk>"))) }
        private fun sendNoPermission(player: Player) { player.sendMessage(CrabMessages.error("You do not have permission to use that slime command.")) }
    }
}
