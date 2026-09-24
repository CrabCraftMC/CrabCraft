package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import de.maxhenkel.voicechat.api.BukkitVoicechatService
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/** Loads Simple Voice Chat API types only after the soft dependency is present. */
object VoicechatIntegration {
    @JvmStatic
    fun register(plugin: CrabUtilities): AutoCloseable? {
        val service = plugin.server.servicesManager.load(BukkitVoicechatService::class.java) ?: return null
        val voicechatPlugin = CrabVoicechatPlugin(plugin)
        service.registerPlugin(voicechatPlugin)
        val quitListener = object : Listener {}
        plugin.server.pluginManager.registerEvent(
            PlayerQuitEvent::class.java,
            quitListener,
            EventPriority.LOWEST,
            { _, event -> voicechatPlugin.beforePlayerQuit((event as PlayerQuitEvent).player.uniqueId) },
            plugin,
        )
        plugin.logger.info("Registered Simple Voice Chat plugin")
        return AutoCloseable {
            try {
                voicechatPlugin.shutdown()
            } finally {
                HandlerList.unregisterAll(quitListener)
                plugin.server.servicesManager.unregister(voicechatPlugin)
            }
        }
    }
}
