package crabcraft.net.bingotest

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.bingo.BingoDetector
import java.util.Locale
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class BingoTestCommand(private val manager: BingoTestManager, detectors: List<BingoDetector>) : CommandExecutor, TabCompleter {
    private val detectors = java.util.List.copyOf(detectors)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(CrabMessages.error("This detector checklist is only available in game."))
            return true
        }
        if (args.isEmpty() || args[0].equals("list", ignoreCase = true)) {
            manager.sendChecklist(sender)
            return true
        }
        if (args[0].equals("reset", ignoreCase = true) && args.size == 1) {
            detectors.forEach { it.resetPlayer(sender.uniqueId) }
            manager.reset(sender)
            return true
        }
        if (args[0].equals("details", ignoreCase = true) && args.size == 2) {
            try {
                manager.sendDetails(sender, args[1].toInt())
            } catch (exception: NumberFormatException) {
                sender.sendMessage(CrabMessages.error("Usage: /$label details <1-16>"))
            }
            return true
        }
        sender.sendMessage(CrabMessages.error("Usage: /$label [list|details <1-16>|reset]"))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase(Locale.ROOT)
            return listOf("list", "details", "reset").filter { it.startsWith(prefix) }
        }
        if (args.size == 2 && args[0].equals("details", ignoreCase = true)) {
            return (1..manager.taskCount()).map { it.toString() }.filter { it.startsWith(args[1]) }
        }
        return emptyList()
    }
}
