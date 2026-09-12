package crabcraft.net.crabUtilities.xaero

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.Messenger
import org.jetbrains.annotations.NotNull
import java.util.Arrays
import java.util.concurrent.ThreadLocalRandom

/** Starts Xaero map identity and persists a randomly generated backend ID when required. */
object XaeroBootstrap {
    @JvmStatic
    fun enable(plugin: CrabUtilities) {
        val config = plugin.getConfig()
        if (!config.getBoolean("mod-protocols.xaero-map.enabled", true)) {
            plugin.getLogger().info("Xaero map integration disabled in config."); return
        }
        var serverId = config.getInt("mod-protocols.xaero-map.server-id", 0)
        if (serverId == 0) {
            serverId = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)
            plugin.saveModuleConfigValues(mapOf("mod-protocols.xaero-map.server-id" to serverId))
        }
        val messenger = plugin.getServer().getMessenger()
        for (channel in XaeroIntegration.CHANNELS) messenger.registerOutgoingPluginChannel(plugin, channel)
        plugin.getServer().getPluginManager().registerEvents(XaeroIntegration(plugin, serverId), plugin)
        plugin.getServer().getScheduler().runTask(plugin, Runnable { warnAboutOtherProviders(plugin) })
        plugin.getLogger().info("Xaero map integration enabled (server id $serverId)")
    }
    private fun warnAboutOtherProviders(plugin: CrabUtilities) {
        val messenger = plugin.getServer().getMessenger()
        val otherPlugins = plugin.getServer().getPluginManager().getPlugins().filter { it !== plugin }
            .filter { it.isEnabled }.filter { registersXaeroChannel(messenger, it) }.map { it.getName() }.sorted()
        if (otherPlugins.isNotEmpty()) {
            plugin.getLogger().warning("Other plugins also send on Xaero map channels: " + otherPlugins.joinToString(", ") +
                ". Disable their server/world ID feature to prevent map identity conflicts.")
        }
        if (hasBuiltInLeavesProtocol(plugin)) plugin.getLogger().warning("This server includes Leaves' built-in Xaero map protocol. " +
            "Ensure it is disabled while CrabUtilities provides the server ID.")
    }
    private fun registersXaeroChannel(messenger: Messenger, candidate: Plugin): Boolean =
        XaeroIntegration.CHANNELS.any { messenger.getOutgoingChannels(candidate).contains(it) }
    private fun hasBuiltInLeavesProtocol(plugin: CrabUtilities): Boolean = try {
        Class.forName("org.leavesmc.leaves.protocol.XaeroMapProtocol", false, plugin.getServer().javaClass.classLoader)
        true
    } catch (ignored: ClassNotFoundException) { false } catch (ignored: LinkageError) { false }
}
