package crabcraft.net.crabUtilities

import crabcraft.net.crabUtilities.config.ModuleConfigException
import crabcraft.net.crabUtilities.update.UpdateCommand
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceCommand
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

open class ReloadCommand(
    private val plugin: CrabUtilities,
    private val updateCommand: UpdateCommand?,
    private val viewDistanceCommand: ViewDistanceCommand,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("reload", true)) {
            if (!sender.hasPermission("crabutilities.reload")) {
                sender.sendMessage(CrabMessages.error("You don't have permission to reload CrabUtilities."))
                return true
            }
            if (args.size > 2) {
                sendReloadUsage(sender)
                return true
            }
            val target = if (args.size == 2) args[1].lowercase(Locale.ROOT) else "all"
            if (target !in plugin.getConfigReloadTargets()) {
                sendReloadUsage(sender)
                return true
            }
            val startedAt = System.nanoTime()
            sender.sendMessage(CrabMessages.text(reloadingMessage(target)))
            val messages =
                try {
                    plugin.reloadRuntimeConfig(target)
                } catch (e: ModuleConfigException) {
                    sender.sendMessage(
                        CrabMessages.error("Reload failed (${elapsedMillis(startedAt)} ms): ${e.message}")
                    )
                    return true
                }
            for (message in messages) plugin.logger.info("Reload $target: $message")
            sender.sendMessage(CrabMessages.success(reloadedMessage(elapsedMillis(startedAt))))
            return true
        }
        if (args.isNotEmpty() && args[0].equals("update", true) && updateCommand != null)
            return updateCommand.handle(sender, args)
        if (args.isNotEmpty() && args[0].equals("viewdistance", true)) return viewDistanceCommand.handle(sender, args)
        sender.sendMessage(CrabMessages.error("Usage: /crabutilities <reload|update|viewdistance>"))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): List<String> {
        if (args.size == 1)
            return listOf("reload", "update", "viewdistance").filter { it.startsWith(args[0].lowercase(Locale.ROOT)) }
        if (args.size == 2 && args[0].equals("reload", true))
            return plugin.getConfigReloadTargets().filter { it.startsWith(args[1].lowercase(Locale.ROOT)) }
        if (args.size >= 2 && args[0].equals("update", true) && updateCommand != null)
            return updateCommand.tabComplete(args)
        if (args.size >= 2 && args[0].equals("viewdistance", true)) return viewDistanceCommand.tabComplete(sender, args)
        return emptyList()
    }

    private fun sendReloadUsage(sender: CommandSender) {
        sender.sendMessage(
            CrabMessages.error("Usage: /crabutilities reload [${plugin.getConfigReloadTargets().joinToString("|")}]")
        )
    }

    companion object {
        @JvmStatic fun reloadingMessage(target: String) = "Reloading $target..."

        @JvmStatic fun reloadedMessage(elapsedMillis: Long) = "Reloaded ($elapsedMillis ms)"

        private fun elapsedMillis(startedAt: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }
}
