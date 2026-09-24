package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.messaging.PluginMessageListener

/** Simple Voice Animations compatibility version 3 play protocol. */
class SimpleVoiceAnimationsIntegration(private val plugin: CrabUtilities) : Listener, PluginMessageListener {
    private val preferences = ConcurrentHashMap<UUID, Preferences>()
    private val synchronisedClients = ConcurrentHashMap.newKeySet<UUID>()
    private var active = false

    fun start() {
        if (active) return
        val messenger = plugin.server.messenger
        messenger.registerIncomingPluginChannel(plugin, UPDATE_PREFERENCES_CHANNEL, this)
        messenger.registerOutgoingPluginChannel(plugin, PLAYER_PREFERENCES_CHANNEL)
        plugin.server.pluginManager.registerEvents(this, plugin)
        active = true
        plugin.server.onlinePlayers.forEach(::initialisePlayer)
    }

    fun shutdown() {
        active = false
        HandlerList.unregisterAll(this)
        val messenger = plugin.server.messenger
        messenger.unregisterIncomingPluginChannel(plugin, UPDATE_PREFERENCES_CHANNEL, this)
        messenger.unregisterOutgoingPluginChannel(plugin, PLAYER_PREFERENCES_CHANNEL)
        synchronisedClients.clear()
        preferences.clear()
    }

    fun isActive() = active

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (!active || channel != UPDATE_PREFERENCES_CHANNEL) return
        val updated =
            try {
                decodePreferences(message)
            } catch (_: IllegalArgumentException) {
                plugin.logger.fine("Ignored malformed Simple Voice Animations preferences from ${player.name}")
                return
            }
        preferences[player.uniqueId] = updated
        synchroniseClient(player)
        broadcastPreferences(player.uniqueId, updated)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        initialisePlayer(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (event.channel != PLAYER_PREFERENCES_CHANNEL) return
        preferences.putIfAbsent(event.player.uniqueId, DEFAULT_PREFERENCES)
        synchroniseClient(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        synchronisedClients.remove(event.player.uniqueId)
        preferences.remove(event.player.uniqueId)
    }

    private fun initialisePlayer(player: Player) {
        val initial = preferences.putIfAbsent(player.uniqueId, DEFAULT_PREFERENCES)
        if (PLAYER_PREFERENCES_CHANNEL in player.listeningPluginChannels) synchroniseClient(player)
        broadcastPreferences(player.uniqueId, initial ?: DEFAULT_PREFERENCES)
    }

    private fun synchroniseClient(player: Player) {
        if (!synchronisedClients.add(player.uniqueId)) return
        preferences.forEach { (id, prefs) -> sendPreferences(player, id, prefs) }
    }

    private fun broadcastPreferences(playerId: UUID, prefs: Preferences) {
        for (recipient in plugin.server.onlinePlayers) if (recipient.uniqueId in synchronisedClients)
            sendPreferences(recipient, playerId, prefs)
    }

    private fun sendPreferences(recipient: Player, playerId: UUID, prefs: Preferences) {
        recipient.sendPluginMessage(plugin, PLAYER_PREFERENCES_CHANNEL, encodePlayerPreferences(playerId, prefs))
    }

    data class Preferences(val headAnimationStyle: Int, val splitHeight: Float, val intensity: Float) {
        fun headAnimationStyle() = headAnimationStyle

        fun splitHeight() = splitHeight

        fun intensity() = intensity
    }

    companion object {
        const val UPDATE_PREFERENCES_CHANNEL = "simplevoiceanimations:update_preferences"
        const val PLAYER_PREFERENCES_CHANNEL = "simplevoiceanimations:player_preferences"
        private const val HEAD_ANIMATION_STYLE_COUNT = 6
        private val DEFAULT_PREFERENCES = Preferences(0, 2F, 1F)

        @JvmStatic
        fun decodePreferences(data: ByteArray): Preferences {
            val buffer = ByteBuffer.wrap(data)
            try {
                val style = readVarInt(buffer)
                require(style in 0 until HEAD_ANIMATION_STYLE_COUNT) { "Unknown head animation style" }
                require(buffer.remaining() == Float.SIZE_BYTES * 2) { "Unexpected preferences payload length" }
                return Preferences(style, clamp(buffer.float, 0.5F, 7.5F, 0.5F), clamp(buffer.float, 0F, 2F, 1F))
            } catch (e: BufferUnderflowException) {
                throw IllegalArgumentException("Truncated preferences payload", e)
            }
        }

        @JvmStatic
        fun encodePlayerPreferences(playerId: UUID, prefs: Preferences): ByteArray =
            ByteBuffer.allocate(Long.SIZE_BYTES * 2 + 1 + Float.SIZE_BYTES * 2)
                .putLong(playerId.mostSignificantBits)
                .putLong(playerId.leastSignificantBits)
                .put(prefs.headAnimationStyle.toByte())
                .putFloat(prefs.splitHeight)
                .putFloat(prefs.intensity)
                .array()

        private fun readVarInt(buffer: ByteBuffer): Int {
            var value = 0
            for (index in 0 until 5) {
                val current = buffer.get().toInt()
                value = value or ((current and 0x7F) shl (index * 7))
                if (current and 0x80 == 0) return value
            }
            throw IllegalArgumentException("VarInt is too large")
        }

        private fun clamp(value: Float, minimum: Float, maximum: Float, fallback: Float) =
            if (value.isFinite()) value.coerceIn(minimum, maximum) else fallback
    }
}
