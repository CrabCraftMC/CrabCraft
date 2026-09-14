package crabcraft.net.crabUtilities.voicechat

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.plugin.messaging.PluginMessageListener
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Spigot implementation of the Simple Voice Animations compatibility version 3 play protocol. */
class SimpleVoiceAnimationsIntegration(private val plugin: CrabUtilities) : Listener, PluginMessageListener {
    private val preferences = ConcurrentHashMap<UUID, Preferences>()
    private val synchronisedClients = ConcurrentHashMap.newKeySet<UUID>()
    private var active = false

    fun start() {
        if (active) return
        val messenger = plugin.getServer().getMessenger()
        messenger.registerIncomingPluginChannel(plugin, UPDATE_PREFERENCES_CHANNEL, this)
        messenger.registerOutgoingPluginChannel(plugin, PLAYER_PREFERENCES_CHANNEL)
        plugin.getServer().getPluginManager().registerEvents(this, plugin)
        active = true
        for (player in plugin.getServer().getOnlinePlayers()) initialisePlayer(player)
    }

    fun shutdown() {
        active = false
        HandlerList.unregisterAll(this)
        val messenger = plugin.getServer().getMessenger()
        messenger.unregisterIncomingPluginChannel(plugin, UPDATE_PREFERENCES_CHANNEL, this)
        messenger.unregisterOutgoingPluginChannel(plugin, PLAYER_PREFERENCES_CHANNEL)
        synchronisedClients.clear()
        preferences.clear()
    }

    fun isActive(): Boolean = active

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (!active || UPDATE_PREFERENCES_CHANNEL != channel) return
        val updated = try { decodePreferences(message) } catch (exception: IllegalArgumentException) {
            plugin.getLogger().fine("Ignored malformed Simple Voice Animations preferences from " + player.getName())
            return
        }
        val playerId = player.getUniqueId()
        preferences[playerId] = updated
        synchroniseClient(player)
        broadcastPreferences(playerId, updated)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) { initialisePlayer(event.getPlayer()) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (PLAYER_PREFERENCES_CHANNEL != event.getChannel()) return
        val player = event.getPlayer()
        preferences.putIfAbsent(player.getUniqueId(), DEFAULT_PREFERENCES)
        synchroniseClient(player)
    }

    @EventHandler fun onPlayerQuit(event: PlayerQuitEvent) {
        val playerId = event.getPlayer().getUniqueId()
        synchronisedClients.remove(playerId)
        preferences.remove(playerId)
    }

    private fun initialisePlayer(player: Player) {
        val playerId = player.getUniqueId()
        val initial = preferences.putIfAbsent(playerId, DEFAULT_PREFERENCES)
        if (player.getListeningPluginChannels().contains(PLAYER_PREFERENCES_CHANNEL)) synchroniseClient(player)
        broadcastPreferences(playerId, initial ?: DEFAULT_PREFERENCES)
    }

    private fun synchroniseClient(player: Player) {
        if (!synchronisedClients.add(player.getUniqueId())) return
        preferences.forEach { (playerId, playerPreferences) -> sendPreferences(player, playerId, playerPreferences) }
    }

    private fun broadcastPreferences(playerId: UUID, playerPreferences: Preferences) {
        for (recipient in plugin.getServer().getOnlinePlayers()) {
            if (synchronisedClients.contains(recipient.getUniqueId())) sendPreferences(recipient, playerId, playerPreferences)
        }
    }

    private fun sendPreferences(recipient: Player, playerId: UUID, playerPreferences: Preferences) {
        recipient.sendPluginMessage(plugin, PLAYER_PREFERENCES_CHANNEL, encodePlayerPreferences(playerId, playerPreferences))
    }

    data class Preferences(private val headAnimationStyle: Int, private val splitHeight: Float, private val intensity: Float) {
        fun headAnimationStyle(): Int = headAnimationStyle
        fun splitHeight(): Float = splitHeight
        fun intensity(): Float = intensity
    }

    companion object {
        const val UPDATE_PREFERENCES_CHANNEL = "simplevoiceanimations:update_preferences"
        const val PLAYER_PREFERENCES_CHANNEL = "simplevoiceanimations:player_preferences"
        private const val HEAD_ANIMATION_STYLE_COUNT = 6
        private const val MIN_SPLIT_HEIGHT = 0.5F
        private const val MAX_SPLIT_HEIGHT = 7.5F
        private const val DEFAULT_SPLIT_HEIGHT = 0.5F
        private const val MIN_INTENSITY = 0F
        private const val MAX_INTENSITY = 2F
        private const val DEFAULT_INTENSITY = 1F
        private val DEFAULT_PREFERENCES = Preferences(0, 2F, DEFAULT_INTENSITY)

        @JvmStatic fun decodePreferences(data: ByteArray): Preferences {
            val buffer = ByteBuffer.wrap(data)
            try {
                val headAnimationStyle = readVarInt(buffer)
                if (headAnimationStyle < 0 || headAnimationStyle >= HEAD_ANIMATION_STYLE_COUNT) throw IllegalArgumentException("Unknown head animation style")
                if (buffer.remaining() != java.lang.Float.BYTES * 2) throw IllegalArgumentException("Unexpected preferences payload length")
                val splitHeight = clamp(buffer.getFloat(), MIN_SPLIT_HEIGHT, MAX_SPLIT_HEIGHT, DEFAULT_SPLIT_HEIGHT)
                val intensity = clamp(buffer.getFloat(), MIN_INTENSITY, MAX_INTENSITY, DEFAULT_INTENSITY)
                return Preferences(headAnimationStyle, splitHeight, intensity)
            } catch (exception: BufferUnderflowException) { throw IllegalArgumentException("Truncated preferences payload", exception) }
        }

        @JvmStatic fun encodePlayerPreferences(playerId: UUID, playerPreferences: Preferences): ByteArray {
            val buffer = ByteBuffer.allocate(java.lang.Long.BYTES * 2 + 1 + java.lang.Float.BYTES * 2)
            buffer.putLong(playerId.mostSignificantBits)
            buffer.putLong(playerId.leastSignificantBits)
            buffer.put(playerPreferences.headAnimationStyle().toByte())
            buffer.putFloat(playerPreferences.splitHeight())
            buffer.putFloat(playerPreferences.intensity())
            return buffer.array()
        }

        private fun readVarInt(buffer: ByteBuffer): Int {
            var value = 0
            for (byteIndex in 0 until 5) {
                val current = buffer.get().toInt()
                value = value or ((current and 0x7F) shl (byteIndex * 7))
                if ((current and 0x80) == 0) return value
            }
            throw IllegalArgumentException("VarInt is too large")
        }
        private fun clamp(value: Float, minimum: Float, maximum: Float, fallback: Float): Float {
            if (!value.isFinite()) return fallback
            return Math.max(minimum, Math.min(maximum, value))
        }
    }
}
