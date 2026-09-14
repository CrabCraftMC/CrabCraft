package crabcraft.net.crabUtilities

import crabcraft.net.crabUtilities.config.ModuleConfigException
import crabcraft.net.crabUtilities.update.UpdateCommand
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceCommand
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Locale
import java.util.concurrent.TimeUnit

open class ReloadCommand(
    private val plugin: CrabUtilities,
    private val updateCommand: UpdateCommand?,
    private val viewDistanceCommand: ViewDistanceCommand
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            if (!sender.hasPermission("crabutilities.reload")) {
                sender.sendMessage(CrabMessages.error("You don't have permission to reload CrabUtilities."))
                return true
            }
            if (args.size > 2) {
                sendReloadUsage(sender)
                return true
            }
            val target = if (args.size == 2) args[1].lowercase(Locale.ROOT) else "all"
            if (!plugin.getConfigReloadTargets().contains(target)) {
                sendReloadUsage(sender)
                return true
            }
            val startedAt = System.nanoTime()
            sender.sendMessage(CrabMessages.text(reloadingMessage(target)))
            val messages = try {
                plugin.reloadRuntimeConfig(target)
            } catch (e: ModuleConfigException) {
                sender.sendMessage(CrabMessages.error("Reload failed (" + elapsedMillis(startedAt) + " ms): " + e.message))
                return true
            }
            for (message in messages) plugin.getLogger().info("Reload " + target + ": " + message)
            sender.sendMessage(CrabMessages.success(reloadedMessage(elapsedMillis(startedAt))))
            return true
        }
        if (args.isNotEmpty() && args[0].equals("update", ignoreCase = true) && updateCommand != null) {
            return updateCommand.handle(sender, args)
        }
        if (args.isNotEmpty() && args[0].equals("viewdistance", ignoreCase = true)) {
            return viewDistanceCommand.handle(sender, args)
        }
        sender.sendMessage(CrabMessages.error("Usage: /crabutilities <reload|update|viewdistance>"))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            val opts = listOf("reload", "update", "viewdistance")
            val p = args[0].lowercase(Locale.ROOT)
            return opts.filter { it.startsWith(p) }
        }
        if (args.size == 2 && args[0].equals("reload", ignoreCase = true)) {
            val prefix = args[1].lowercase(Locale.ROOT)
            return plugin.getConfigReloadTargets().filter { it.startsWith(prefix) }
        }
        if (args.size >= 2 && args[0].equals("update", ignoreCase = true) && updateCommand != null) {
            return updateCommand.tabComplete(args)
        }
        if (args.size >= 2 && args[0].equals("viewdistance", ignoreCase = true)) {
            return viewDistanceCommand.tabComplete(sender, args)
        }
        return emptyList()
    }

    private fun sendReloadUsage(sender: CommandSender) {
        sender.sendMessage(CrabMessages.error("Usage: /crabutilities reload [" + plugin.getConfigReloadTargets().joinToString("|") + "]"))
    }

    companion object {
        @JvmStatic
        fun reloadingMessage(target: String): String = "Reloading " + target + "..."

        @JvmStatic
        fun reloadedMessage(elapsedMillis: Long): String = "Reloaded (" + elapsedMillis + " ms)"

        private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }
}
