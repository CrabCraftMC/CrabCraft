package crabcraft.net.crabUtilities.xaero

import crabcraft.net.crabUtilities.CrabUtilities
import java.util.concurrent.ThreadLocalRandom
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.Messenger

/** Resolves and persists a stable backend identity for Xaero map clients. */
object XaeroBootstrap {
    @JvmStatic
    fun enable(plugin: CrabUtilities) {
        val config = plugin.config
        if (!config.getBoolean("mod-protocols.xaero-map.enabled", true)) {
            plugin.logger.info("Xaero map integration disabled in config.")
            return
        }
        var serverId = config.getInt("mod-protocols.xaero-map.server-id", 0)
        if (serverId == 0) {
            serverId = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)
            plugin.saveModuleConfigValues(mapOf("mod-protocols.xaero-map.server-id" to serverId))
        }
        val messenger = plugin.server.messenger
        for (channel in XaeroIntegration.CHANNELS) messenger.registerOutgoingPluginChannel(plugin, channel)
        plugin.server.pluginManager.registerEvents(XaeroIntegration(plugin, serverId), plugin)
        plugin.server.scheduler.runTask(plugin, Runnable { warnAboutOtherProviders(plugin) })
        plugin.logger.info("Xaero map integration enabled (server id $serverId)")
    }

    private fun warnAboutOtherProviders(plugin: CrabUtilities) {
        val messenger = plugin.server.messenger
        val otherPlugins =
            plugin.server.pluginManager.plugins
                .filter {
                    it !== plugin && it.isEnabled && registersXaeroChannel(messenger, it)
                }
                .map { it.name }
                .sorted()
        if (otherPlugins.isNotEmpty())
            plugin.logger.warning(
                "Other plugins also send on Xaero map channels: ${otherPlugins.joinToString(", ")}. " +
                    "Disable their server/world ID feature to prevent map identity conflicts."
            )
        if (hasBuiltInLeavesProtocol(plugin))
            plugin.logger.warning(
                "This server includes Leaves' built-in Xaero map protocol. " +
                    "Ensure it is disabled while CrabUtilities provides the server ID."
            )
    }

    private fun registersXaeroChannel(messenger: Messenger, candidate: Plugin) =
        XaeroIntegration.CHANNELS.any { messenger.getOutgoingChannels(candidate).contains(it) }

    private fun hasBuiltInLeavesProtocol(plugin: CrabUtilities): Boolean =
        try {
            Class.forName("org.leavesmc.leaves.protocol.XaeroMapProtocol", false, plugin.server.javaClass.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
}
