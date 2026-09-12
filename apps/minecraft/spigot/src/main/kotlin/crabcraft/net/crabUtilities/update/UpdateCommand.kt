package crabcraft.net.crabUtilities.update

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import java.time.Duration
import java.time.Instant
import java.util.Arrays
import java.util.Collections

open class UpdateCommand(private val plugin: CrabUtilities, private val service: UpdateService) {
    open fun handle(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("crabutilities.update")) {
            sender.sendMessage(CrabMessages.error("You don't have permission to run update commands.")); return true
        }
        if (args.size < 2) { sendUsage(sender); return true }
        when (args[1].lowercase(java.util.Locale.getDefault())) {
            "check" -> runAsync(sender, false)
            "download" -> runAsync(sender, true)
            "status" -> sendStatus(sender)
            else -> sendUsage(sender)
        }
        return true
    }
    open fun tabComplete(args: Array<String>): List<String> = if (args.size == 2) filter(listOf("check", "download", "status"), args[1]) else emptyList()
    private fun runAsync(sender: CommandSender, download: Boolean) {
        sender.sendMessage(CrabMessages.text(if (download) "Checking and downloading..." else "Checking for updates..."))
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            service.runCheck(download) { msg -> Bukkit.getScheduler().runTask(plugin, Runnable { sender.sendMessage(reportMessage(msg)) }) }
        })
    }
    private fun sendStatus(sender: CommandSender) {
        val s = service.getState()
        val last = service.getLastCheck()
        val seen = service.getLastSeen()
        val err = service.getLastError()
        sender.sendMessage(CrabMessages.accent("CrabUtilities update status"))
        sender.sendMessage(CrabMessages.label("Running", plugin.getDescription().getVersion()))
        sender.sendMessage(CrabMessages.label("State", stateComponent(s)))
        sender.sendMessage(CrabMessages.label("Last check", if (last == null) "never" else prettyAgo(last)))
        if (seen != null) {
            var latest = CrabMessages.text(seen.tag())
            if (seen.prerelease()) latest = latest.append(CrabMessages.warning(" (pre-release)"))
            sender.sendMessage(CrabMessages.label("Latest seen", latest))
        }
        if (err != null) sender.sendMessage(CrabMessages.label("Last error", CrabMessages.error(err)))
    }
    private fun sendUsage(sender: CommandSender) { sender.sendMessage(CrabMessages.error("Usage: /crabutilities update <check|download|status>")) }
    private fun reportMessage(message: String): Component = when (service.getState()) {
        UpdateService.State.ERROR -> CrabMessages.error(message)
        UpdateService.State.READY, UpdateService.State.UP_TO_DATE -> CrabMessages.success(message)
        UpdateService.State.CHECKING, UpdateService.State.DOWNLOADING -> CrabMessages.warning(message)
        UpdateService.State.IDLE -> CrabMessages.text(message)
    }
    companion object {
        private fun stateComponent(state: UpdateService.State): Component {
            val label = state.name.lowercase(java.util.Locale.getDefault())
            return when (state) {
                UpdateService.State.ERROR -> CrabMessages.error(label)
                UpdateService.State.READY, UpdateService.State.UP_TO_DATE -> CrabMessages.success(label)
                UpdateService.State.CHECKING, UpdateService.State.DOWNLOADING -> CrabMessages.warning(label)
                UpdateService.State.IDLE -> CrabMessages.text(label)
            }
        }
        private fun prettyAgo(whenChecked: Instant): String {
            val s = Duration.between(whenChecked, Instant.now()).seconds
            if (s < 60) return "${s}s ago"
            if (s < 3600) return "${s / 60}m ago"
            if (s < 86400) return "${s / 3600}h ago"
            return "${s / 86400}d ago"
        }
        private fun filter(opts: List<String>, prefix: String): List<String> {
            val p = prefix.lowercase(java.util.Locale.getDefault())
            return opts.filter { it.startsWith(p) }
        }
    }
}
