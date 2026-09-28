package crabcraft.net.crabUtilities.update

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.CrabUtilities
import java.time.Duration
import java.time.Instant
import java.util.Locale
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

open class UpdateCommand(private val plugin: CrabUtilities, private val service: UpdateService) {
    open fun handle(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("crabutilities.update")) {
            sender.sendMessage(CrabMessages.error("You don't have permission to run update commands."))
            return true
        }
        if (args.size < 2) {
            sendUsage(sender)
            return true
        }
        when (args[1].lowercase(Locale.getDefault())) {
            "check" -> runAsync(sender, false)
            "download" -> runAsync(sender, true)
            "status" -> sendStatus(sender)
            else -> sendUsage(sender)
        }
        return true
    }

    open fun tabComplete(args: Array<String>): List<String> =
        if (args.size == 2) {
            val prefix = args[1].lowercase(Locale.getDefault())
            listOf("check", "download", "status").filter { it.startsWith(prefix) }
        } else emptyList()

    private fun runAsync(sender: CommandSender, download: Boolean) {
        sender.sendMessage(
            CrabMessages.text(if (download) "Checking and downloading..." else "Checking for updates...")
        )
        Bukkit.getScheduler()
            .runTaskAsynchronously(
                plugin,
                Runnable {
                    service.runCheck(download) { message ->
                        Bukkit.getScheduler().runTask(plugin, Runnable { sender.sendMessage(reportMessage(message)) })
                    }
                },
            )
    }

    private fun sendStatus(sender: CommandSender) {
        val state = service.getState()
        val last = service.getLastCheck()
        val seen = service.getLastSeen()
        val error = service.getLastError()
        sender.sendMessage(CrabMessages.accent("CrabUtilities update status"))
        sender.sendMessage(CrabMessages.label("Running", plugin.getDescription().version))
        sender.sendMessage(CrabMessages.label("State", stateComponent(state)))
        sender.sendMessage(CrabMessages.label("Last check", last?.let(::prettyAgo) ?: "never"))
        if (seen != null) {
            var latest = CrabMessages.text(seen.tag())
            if (seen.prerelease()) latest = latest.append(CrabMessages.warning(" (pre-release)"))
            sender.sendMessage(CrabMessages.label("Latest seen", latest))
        }
        if (error != null) sender.sendMessage(CrabMessages.label("Last error", CrabMessages.error(error)))
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(CrabMessages.error("Usage: /crabutilities update <check|download|status>"))
    }

    private fun reportMessage(message: String): Component = style(service.getState(), message)

    private fun stateComponent(state: UpdateService.State) = style(state, state.name.lowercase(Locale.getDefault()))

    private fun style(state: UpdateService.State, message: String): Component =
        when (state) {
            UpdateService.State.ERROR -> CrabMessages.error(message)
            UpdateService.State.READY,
            UpdateService.State.UP_TO_DATE -> CrabMessages.success(message)
            UpdateService.State.CHECKING,
            UpdateService.State.DOWNLOADING -> CrabMessages.warning(message)
            UpdateService.State.IDLE -> CrabMessages.text(message)
        }

    private fun prettyAgo(whenChecked: Instant): String {
        val seconds = Duration.between(whenChecked, Instant.now()).seconds
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }
}
