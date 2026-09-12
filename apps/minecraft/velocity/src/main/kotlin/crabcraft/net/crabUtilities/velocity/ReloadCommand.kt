package crabcraft.net.crabUtilities.velocity

import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

import java.time.Duration
import java.time.Instant

open class ReloadCommand {
    companion object {

        @JvmStatic fun register(plugin: CrabUtilitiesVelocity) {
            val node = BrigadierCommand.literalArgumentBuilder("crabutilitiesproxy")
                    .requires { source -> source.hasPermission("crabutilities.reload")
                            || source.hasPermission("crabutilities.webapi")
                            || source.hasPermission("crabutilities.update") }
                    .then(BrigadierCommand.literalArgumentBuilder("reload")
                            .requires { source -> source.hasPermission("crabutilities.reload") }
                            .executes { ctx ->
                                plugin.reload()
                                ctx.getSource().sendMessage(Component.text(
                                        "CrabUtilities Velocity config reloaded.", NamedTextColor.GREEN))
                                1
                            }
                    )
                    .then(BrigadierCommand.literalArgumentBuilder("webapi")
                            .requires { source -> source.hasPermission("crabutilities.webapi") }
                            .then(BrigadierCommand.literalArgumentBuilder("start")
                                    .executes { ctx ->
                                        if (plugin.getWebServer()!!.start()) {
                                            ctx.getSource().sendMessage(Component.text(
                                                    "Web API started.", NamedTextColor.GREEN))
                                        } else {
                                            ctx.getSource().sendMessage(Component.text(
                                                    "Web API is already running or failed to start. Check console for details.", NamedTextColor.RED))
                                        }
                                        1
                                    }
                            )
                            .then(BrigadierCommand.literalArgumentBuilder("stop")
                                    .executes { ctx ->
                                        if (plugin.getWebServer()!!.isRunning()) {
                                            plugin.getWebServer()!!.stop()
                                            ctx.getSource().sendMessage(Component.text(
                                                    "Web API stopped.", NamedTextColor.GREEN))
                                        } else {
                                            ctx.getSource().sendMessage(Component.text(
                                                    "Web API is not running.", NamedTextColor.RED))
                                        }
                                        1
                                    }
                            )
                            .executes { ctx ->
                                ctx.getSource().sendMessage(Component.text(
                                        "Usage: /crabutilitiesproxy webapi <start|stop>", NamedTextColor.RED))
                                0
                            }
                    )
                    .then(BrigadierCommand.literalArgumentBuilder("update")
                            .requires { source -> source.hasPermission("crabutilities.update") }
                            .then(BrigadierCommand.literalArgumentBuilder("check")
                                    .executes { ctx -> runCheck(plugin, ctx.source, false) })
                            .then(BrigadierCommand.literalArgumentBuilder("download")
                                    .executes { ctx -> runCheck(plugin, ctx.source, true) })
                            .then(BrigadierCommand.literalArgumentBuilder("status")
                                    .executes { ctx ->
                                        sendStatus(plugin, ctx.getSource())
                                        1
                                    })
                            .executes { ctx ->
                                ctx.getSource().sendMessage(Component.text(
                                        "Usage: /crabutilitiesproxy update <check|download|status>",
                                        NamedTextColor.RED))
                                0
                            }
                    )
                    .executes { ctx ->
                        ctx.getSource().sendMessage(Component.text(
                                "Usage: /crabutilitiesproxy <reload|webapi|update>", NamedTextColor.RED))
                        0
                    }
                    .build()

            val command = BrigadierCommand(node)
            plugin.getServer().getCommandManager().register(
                    plugin.getServer().getCommandManager().metaBuilder(command)
                            .plugin(plugin)
                            .build(),
                    command
            )
        }

        private fun runCheck(plugin: CrabUtilitiesVelocity, source: CommandSource, download: Boolean): Int {
            val svc = plugin.getUpdateService()
            if (svc == null) {
                source.sendMessage(Component.text("Update service is disabled.", NamedTextColor.RED))
                return 0
            }
            source.sendMessage(Component.text(
                    if (download) "Checking and downloading..." else "Checking for updates...",
                    NamedTextColor.GRAY))
            plugin.getServer().getScheduler().buildTask(plugin, Runnable { svc.runCheck(download, source::sendMessage) }).schedule()
            return 1
        }

        private fun sendStatus(plugin: CrabUtilitiesVelocity, source: CommandSource) {
            val svc = plugin.getUpdateService()
            if (svc == null) {
                source.sendMessage(Component.text("Update service is disabled.", NamedTextColor.RED))
                return
            }
            val s = svc.getState()
            val last = svc.getLastCheck()
            val seen = svc.getLastSeen()
            val err = svc.getLastError()

            source.sendMessage(Component.text("CrabUtilities (Velocity) update status", NamedTextColor.GOLD))
            source.sendMessage(Component.text("Running: " + BuildInfo.VERSION, NamedTextColor.GRAY))
            source.sendMessage(Component.text("State: " + s.name.lowercase(java.util.Locale.getDefault()), NamedTextColor.GRAY))
            source.sendMessage(Component.text(
                    "Last check: " + (if (last == null) "never" else prettyAgo(last)), NamedTextColor.GRAY))
            if (seen != null) {
                source.sendMessage(Component.text("Latest seen: " + seen.tag()
                        + (if (seen.prerelease()) " (pre-release)" else ""), NamedTextColor.GRAY))
            }
            if (err != null) {
                source.sendMessage(Component.text("Last error: " + err, NamedTextColor.RED))
            }
        }

        private fun prettyAgo(instant: Instant): String {
            val d = Duration.between(instant, Instant.now())
            val s = d.getSeconds()
            if (s < 60) return s.toString() + "s ago"
            if (s < 3600) return (s / 60).toString() + "m ago"
            if (s < 86400) return (s / 3600).toString() + "h ago"
            return (s / 86400).toString() + "d ago"
        }
    }
}
