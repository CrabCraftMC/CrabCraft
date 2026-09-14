package crabcraft.net.crabUtilities

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale

open class PackCommand(private val plugin: CrabUtilities) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("Usage: /$label <on|off>")
            return true
        }
        val rpm = plugin.getResourcePackManager()
        when (args[0].lowercase(Locale.getDefault())) {
            "on" -> {
                if (!rpm.isConfigured()) {
                    sender.sendMessage("Resource pack URL is not configured. Ask an admin to set resource-pack.url in config.yml.")
                    return true
                }
                val sent = rpm.enableFor(sender)
                sender.sendMessage(if (sent) "Resource pack enabled. You'll receive it on join."
                    else "Could not send the resource pack. Check the configured URL.")
            }
            "off" -> {
                rpm.disableFor(sender)
                sender.sendMessage("Resource pack auto-send disabled.")
            }
            else -> sender.sendMessage("Usage: /$label <on|off>")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase(Locale.getDefault())
            return listOf("on", "off").filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}
