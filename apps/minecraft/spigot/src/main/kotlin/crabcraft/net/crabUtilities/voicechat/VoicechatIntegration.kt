package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import de.maxhenkel.voicechat.api.BukkitVoicechatService
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/** Loads Simple Voice Chat API types only after the soft dependency is present. */
class VoicechatIntegration private constructor() {
    companion object {
        @JvmStatic fun register(plugin: CrabUtilities): AutoCloseable? {
            val service = plugin.getServer().getServicesManager().load(BukkitVoicechatService::class.java) ?: return null
            val voicechatPlugin = CrabVoicechatPlugin(plugin)
            service.registerPlugin(voicechatPlugin)
            val quitListener = object : Listener {}
            plugin.getServer().getPluginManager().registerEvent(
                PlayerQuitEvent::class.java, quitListener, EventPriority.LOWEST,
                { _, event -> voicechatPlugin.beforePlayerQuit((event as PlayerQuitEvent).getPlayer().getUniqueId()) }, plugin)
            plugin.getLogger().info("Registered Simple Voice Chat plugin")
            return AutoCloseable {
                try {
                    voicechatPlugin.shutdown()
                } finally {
                    HandlerList.unregisterAll(quitListener)
                    plugin.getServer().getServicesManager().unregister(voicechatPlugin)
                }
            }
        }
    }
}
