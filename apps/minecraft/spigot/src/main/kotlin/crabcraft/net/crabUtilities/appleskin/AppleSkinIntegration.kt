package crabcraft.net.crabUtilities.appleskin

import io.papermc.paper.event.world.WorldGameRuleChangeEvent
import org.bukkit.GameRule
import org.bukkit.GameRules
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.java.JavaPlugin
import java.nio.ByteBuffer

class AppleSkinIntegration private constructor() : Listener {
    @EventHandler
    private fun onPlayerRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (event.channel == SATURATION_CHANNEL) {
            AppleSkinSyncTask(event.player)
        } else if (event.channel == NATURAL_REGENERATION_CHANNEL) {
            syncNaturalRegeneration(event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        if (event.player.listeningPluginChannels.contains(NATURAL_REGENERATION_CHANNEL)) {
            syncNaturalRegeneration(event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private fun onGameRuleChange(event: WorldGameRuleChangeEvent) {
        if (event.gameRule != naturalRegenerationRule()) return
        event.world.players.forEach { player ->
            if (player.listeningPluginChannels.contains(NATURAL_REGENERATION_CHANNEL)) {
                sendNaturalRegeneration(player, java.lang.Boolean.parseBoolean(event.value))
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
                val registeredListener = AppleSkinIntegration()
                listener = registeredListener
                plugin.server.pluginManager.registerEvents(registeredListener, plugin)
                val messenger = plugin.server.messenger
                CHANNELS.forEach { channel -> messenger.registerOutgoingPluginChannel(plugin, channel) }
                enabled = true
                plugin.server.onlinePlayers.forEach { player ->
                    if (player.listeningPluginChannels.contains(SATURATION_CHANNEL)) AppleSkinSyncTask(player)
                    if (player.listeningPluginChannels.contains(NATURAL_REGENERATION_CHANNEL)) syncNaturalRegeneration(player)
                }
                plugin.logger.info("AppleSkin integration enabled")
                return true
            } catch (exception: LinkageError) {
                disable(plugin)
                plugin.logger.warning("AppleSkin integration could not initialise: ${exception.message}")
                return false
            } catch (exception: RuntimeException) {
                disable(plugin)
                plugin.logger.warning("AppleSkin integration could not initialise: ${exception.message}")
                return false
            }
        }

        @JvmStatic
        @Synchronized
        fun disable(plugin: JavaPlugin) {
            invalidateTasks()
            listener?.let { HandlerList.unregisterAll(it) }
            listener = null
            val messenger = plugin.server.messenger
            CHANNELS.forEach { channel -> messenger.unregisterOutgoingPluginChannel(plugin, channel) }
        }

        @JvmStatic fun isEnabled(): Boolean = enabled
        @JvmStatic fun currentGeneration(): Long = generation
        @JvmStatic fun isCurrentGeneration(expected: Long): Boolean = enabled && generation == expected
        @JvmStatic fun plugin(): JavaPlugin = java.util.Objects.requireNonNull(plugin, "AppleSkin integration is not enabled")!!
        @JvmStatic
        fun invalidateTasks() {
            enabled = false
            generation++
            plugin = null
        }

        @JvmStatic
        private fun syncNaturalRegeneration(player: Player) {
            sendNaturalRegeneration(player, player.world.getGameRuleValue(naturalRegenerationRule()) == true)
        }

        @JvmStatic
        private fun sendNaturalRegeneration(player: Player, enabled: Boolean) {
            player.sendPluginMessage(plugin(), NATURAL_REGENERATION_CHANNEL,
                ByteBuffer.allocate(1).put((if (enabled) 1 else 0).toByte()).array())
        }

        @JvmStatic
        private fun naturalRegenerationRule(): GameRule<Boolean> = GameRules.NATURAL_HEALTH_REGENERATION
    }
}
