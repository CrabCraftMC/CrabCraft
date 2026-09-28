package crabcraft.net.crabUtilities.appleskin

import io.papermc.paper.event.world.WorldGameRuleChangeEvent
import org.bukkit.GameRules
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.java.JavaPlugin

class AppleSkinIntegration private constructor() : Listener {
    @EventHandler
    private fun onPlayerRegisterChannel(event: PlayerRegisterChannelEvent) {
        when (event.channel) {
            SATURATION_CHANNEL -> AppleSkinSyncTask(event.player)
            NATURAL_REGENERATION_CHANNEL -> syncNaturalRegeneration(event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        if (NATURAL_REGENERATION_CHANNEL in event.player.listeningPluginChannels) syncNaturalRegeneration(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private fun onGameRuleChange(event: WorldGameRuleChangeEvent) {
        if (event.gameRule != naturalRegenerationRule()) return
        event.world.players.forEach { player ->
            if (NATURAL_REGENERATION_CHANNEL in player.listeningPluginChannels) {
                sendNaturalRegeneration(player, event.value.toBoolean())
            }
        }
    }

    companion object {
        const val SATURATION_CHANNEL = "appleskin:saturation"
        const val EXHAUSTION_CHANNEL = "appleskin:exhaustion"
        private const val NATURAL_REGENERATION_CHANNEL = "appleskin:natural_regeneration"
        private val CHANNELS = listOf(SATURATION_CHANNEL, EXHAUSTION_CHANNEL, NATURAL_REGENERATION_CHANNEL)
        private var plugin: JavaPlugin? = null
        private var listener: AppleSkinIntegration? = null
        @Volatile private var enabled = false
        @Volatile private var generation = 0L

        @JvmStatic
        @Synchronized
        fun enable(plugin: JavaPlugin): Boolean {
            if (!plugin.config.getBoolean("mod-protocols.appleskin.enabled", true)) {
                disable(plugin)
                plugin.logger.info("AppleSkin integration disabled in config.")
                return false
            }
            if (enabled) return true
            this.plugin = plugin
            generation++
            try {
                val integration = AppleSkinIntegration()
                listener = integration
                plugin.server.pluginManager.registerEvents(integration, plugin)
                val messenger = plugin.server.messenger
                CHANNELS.forEach { messenger.registerOutgoingPluginChannel(plugin, it) }
                enabled = true
                plugin.server.onlinePlayers.forEach { player ->
                    if (SATURATION_CHANNEL in player.listeningPluginChannels) AppleSkinSyncTask(player)
                    if (NATURAL_REGENERATION_CHANNEL in player.listeningPluginChannels) syncNaturalRegeneration(player)
                }
                plugin.logger.info("AppleSkin integration enabled")
                return true
            } catch (exception: LinkageError) {
                return failedEnable(plugin, exception)
            } catch (exception: RuntimeException) {
                return failedEnable(plugin, exception)
            }
        }

        private fun failedEnable(plugin: JavaPlugin, exception: Throwable): Boolean {
            disable(plugin)
            plugin.logger.warning("AppleSkin integration could not initialise: ${exception.message}")
            return false
        }

        @JvmStatic
        @Synchronized
        fun disable(plugin: JavaPlugin) {
            invalidateTasks()
            listener?.let { HandlerList.unregisterAll(it) }
            listener = null
            val messenger = plugin.server.messenger
            CHANNELS.forEach { messenger.unregisterOutgoingPluginChannel(plugin, it) }
        }

        @JvmStatic fun isEnabled() = enabled

        @JvmStatic fun currentGeneration() = generation

        @JvmStatic fun isCurrentGeneration(expected: Long) = enabled && generation == expected

        @JvmStatic
        fun plugin(): JavaPlugin = java.util.Objects.requireNonNull(plugin, "AppleSkin integration is not enabled")!!

        @JvmStatic
        fun invalidateTasks() {
            enabled = false
            generation++
            plugin = null
        }

        private fun syncNaturalRegeneration(player: Player) {
            sendNaturalRegeneration(player, player.world.getGameRuleValue(naturalRegenerationRule()) == true)
        }

        private fun sendNaturalRegeneration(player: Player, enabled: Boolean) {
            player.sendPluginMessage(plugin(), NATURAL_REGENERATION_CHANNEL, byteArrayOf(if (enabled) 1 else 0))
        }

        private fun naturalRegenerationRule() = GameRules.NATURAL_HEALTH_REGENERATION
    }
}
