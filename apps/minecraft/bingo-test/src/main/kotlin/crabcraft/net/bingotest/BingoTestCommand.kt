package crabcraft.net.bingotest

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.bingo.BingoDetector
import java.util.Locale
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

internal class BingoTestCommand(
    private val manager: BingoTestManager,
    detectors: List<BingoDetector>,
) : CommandExecutor, TabCompleter {
    private val detectors = detectors.toList()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(CrabMessages.error("This detector checklist is only available in game."))
            return true
        }
        when {
            args.isEmpty() || args[0].equals("list", ignoreCase = true) -> manager.sendChecklist(sender)
            args[0].equals("reset", ignoreCase = true) && args.size == 1 -> {
                detectors.forEach { it.resetPlayer(sender.uniqueId) }
                manager.reset(sender)
            }
            args[0].equals("details", ignoreCase = true) && args.size == 2 -> {
                val number = args[1].toIntOrNull()
                if (number != null) manager.sendDetails(sender, number)
                else sender.sendMessage(CrabMessages.error("Usage: /$label details <1-16>"))
            }
            else -> sender.sendMessage(CrabMessages.error("Usage: /$label [list|details <1-16>|reset]"))
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> =
        when {
            args.size == 1 -> {
                val prefix = args[0].lowercase(Locale.ROOT)
                listOf("list", "details", "reset").filter { it.startsWith(prefix) }
            }
            args.size == 2 && args[0].equals("details", ignoreCase = true) ->
                (1..manager.taskCount()).map(Int::toString).filter { it.startsWith(args[1]) }
            else -> emptyList()
        }
}
