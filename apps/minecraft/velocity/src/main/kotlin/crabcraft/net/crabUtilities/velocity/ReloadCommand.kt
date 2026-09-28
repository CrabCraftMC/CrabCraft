package crabcraft.net.crabUtilities.velocity

import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import java.time.Duration
import java.time.Instant
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

open class ReloadCommand {
    companion object {
        @JvmStatic
        fun register(plugin: CrabUtilitiesVelocity) {
            val node =
                BrigadierCommand.literalArgumentBuilder("crabutilitiesproxy")
                    .requires { source ->
                        source.hasPermission("crabutilities.reload") ||
                            source.hasPermission("crabutilities.webapi") ||
                            source.hasPermission("crabutilities.update")
                    }
                    .then(
                        BrigadierCommand.literalArgumentBuilder("reload")
                            .requires { it.hasPermission("crabutilities.reload") }
                            .executes { context ->
                                plugin.reload()
                                context.source.sendMessage(
                                    Component.text("CrabUtilities Velocity config reloaded.", NamedTextColor.GREEN)
                                )
                                1
                            }
                    )
                    .then(
                        BrigadierCommand.literalArgumentBuilder("webapi")
                            .requires { it.hasPermission("crabutilities.webapi") }
                            .then(
                                BrigadierCommand.literalArgumentBuilder("start").executes { context ->
                                    if (plugin.getWebServer()!!.start()) {
                                        context.source.sendMessage(
                                            Component.text("Web API started.", NamedTextColor.GREEN)
                                        )
                                    } else {
                                        context.source.sendMessage(
                                            Component.text(
                                                "Web API is already running or failed to start. Check console for details.",
                                                NamedTextColor.RED,
                                            )
                                        )
                                    }
                                    1
                                }
                            )
                            .then(
                                BrigadierCommand.literalArgumentBuilder("stop").executes { context ->
                                    if (plugin.getWebServer()!!.isRunning()) {
                                        plugin.getWebServer()!!.stop()
                                        context.source.sendMessage(
                                            Component.text("Web API stopped.", NamedTextColor.GREEN)
                                        )
                                    } else
                                        context.source.sendMessage(
                                            Component.text("Web API is not running.", NamedTextColor.RED)
                                        )
                                    1
                                }
                            )
                            .executes { context ->
                                context.source.sendMessage(
                                    Component.text("Usage: /crabutilitiesproxy webapi <start|stop>", NamedTextColor.RED)
                                )
                                0
                            }
                    )
                    .then(
                        BrigadierCommand.literalArgumentBuilder("update")
                            .requires { it.hasPermission("crabutilities.update") }
                            .then(
                                BrigadierCommand.literalArgumentBuilder("check").executes {
                                    runCheck(plugin, it.source, false)
                                }
                            )
                            .then(
                                BrigadierCommand.literalArgumentBuilder("download").executes {
                                    runCheck(plugin, it.source, true)
                                }
                            )
                            .then(
                                BrigadierCommand.literalArgumentBuilder("status").executes {
                                    sendStatus(plugin, it.source)
                                    1
                                }
                            )
                            .executes { context ->
                                context.source.sendMessage(
                                    Component.text(
                                        "Usage: /crabutilitiesproxy update <check|download|status>",
                                        NamedTextColor.RED,
                                    )
                                )
                                0
                            }
                    )
                    .executes { context ->
                        context.source.sendMessage(
                            Component.text("Usage: /crabutilitiesproxy <reload|webapi|update>", NamedTextColor.RED)
                        )
                        0
                    }
                    .build()
            val command = BrigadierCommand(node)
            val manager = plugin.getServer().commandManager
            manager.register(manager.metaBuilder(command).plugin(plugin).build(), command)
        }

        private fun runCheck(plugin: CrabUtilitiesVelocity, source: CommandSource, download: Boolean): Int {
            val service = plugin.getUpdateService()
            if (service == null) {
                source.sendMessage(Component.text("Update service is disabled.", NamedTextColor.RED))
                return 0
            }
            source.sendMessage(
                Component.text(
                    if (download) "Checking and downloading..." else "Checking for updates...",
                    NamedTextColor.GRAY,
                )
            )
            plugin
                .getServer()
                .scheduler
                .buildTask(
                    plugin,
                    Runnable {
                        service.runCheck(download) { source.sendMessage(it) }
                    },
                )
                .schedule()
            return 1
        }

        private fun sendStatus(plugin: CrabUtilitiesVelocity, source: CommandSource) {
            val service = plugin.getUpdateService()
            if (service == null) {
                source.sendMessage(Component.text("Update service is disabled.", NamedTextColor.RED))
                return
            }
            val state = service.getState()
            val last = service.getLastCheck()
            val seen = service.getLastSeen()
            val error = service.getLastError()
            source.sendMessage(Component.text("CrabUtilities (Velocity) update status", NamedTextColor.GOLD))
            source.sendMessage(Component.text("Running: ${BuildInfo.VERSION}", NamedTextColor.GRAY))
            source.sendMessage(
                Component.text("State: ${state.name.lowercase(Locale.getDefault())}", NamedTextColor.GRAY)
            )
            source.sendMessage(Component.text("Last check: ${last?.let(::prettyAgo) ?: "never"}", NamedTextColor.GRAY))
            if (seen != null) {
                source.sendMessage(
                    Component.text(
                        "Latest seen: ${seen.tag()}${if (seen.prerelease()) " (pre-release)" else ""}",
                        NamedTextColor.GRAY,
                    )
                )
            }
            if (error != null) source.sendMessage(Component.text("Last error: $error", NamedTextColor.RED))
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
}
