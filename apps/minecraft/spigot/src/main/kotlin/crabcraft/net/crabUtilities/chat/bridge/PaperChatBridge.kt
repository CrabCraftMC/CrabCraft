package crabcraft.net.crabUtilities.chat.bridge

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.chatbridge.ChatBridgeProtocol
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets Paper own local chat processing while Velocity remains authoritative
 * for cross-server routing and state.
 */
class PaperChatBridge(private val plugin: CrabUtilities) : Listener, PluginMessageListener {
    private val staffChatDisabled: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun start() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, ChatBridgeProtocol.CHANNEL)
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, ChatBridgeProtocol.CHANNEL, this)
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun shutdown() {
        HandlerList.unregisterAll(this)
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, ChatBridgeProtocol.CHANNEL, this)
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, ChatBridgeProtocol.CHANNEL)
        staffChatDisabled.clear()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val command = ChatCommandParser.parse(event.getMessage())
        if (!command.recognised()) return
        event.setCancelled(true)
        val player = event.getPlayer()
        if (!command.valid()) {
            sendUsage(player, command.type())
            return
        }
        try {
            when (command.type()) {
                ChatCommandParser.Type.PRIVATE -> sendToProxy(player,
                    ChatBridgeProtocol.privateRequest(command.target()!!, command.message()!!))
                ChatCommandParser.Type.REPLY -> sendToProxy(player,
                    ChatBridgeProtocol.replyRequest(command.message()!!))
                ChatCommandParser.Type.STAFF -> {
                    if (!player.hasPermission(STAFF_PERMISSION)) {
                        player.sendMessage(Component.text(
                            "You do not have permission to use staff chat.", NamedTextColor.RED))
                        return
                    }
                    sendToProxy(player, ChatBridgeProtocol.staffRequest(
                        GSON.serialize(Component.text(command.message()!!))))
                }
                ChatCommandParser.Type.NONE -> {
                    // Handled by the early return above.
                }
            }
        } catch (e: IllegalArgumentException) {
            player.sendMessage(Component.text("That chat message is too large.", NamedTextColor.RED))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onStaffPrefix(event: AsyncChatEvent) {
        val player = event.getPlayer()
        if (!player.hasPermission(STAFF_PERMISSION)
            || staffChatDisabled.contains(player.getUniqueId())
            || !StaffChatComponents.hasPrefix(event.message())) return
        val staffMessage = StaffChatComponents.removePrefix(event.message())
        if (StaffChatComponents.isEmpty(staffMessage)) return
        // Never let a failed staff-chat bridge leak a staff message into public chat.
        event.setCancelled(true)
        val payload = try {
            ChatBridgeProtocol.staffRequest(GSON.serialize(staffMessage))
        } catch (e: IllegalArgumentException) {
            player.sendMessage(Component.text("That staff message is too large.", NamedTextColor.RED))
            return
        }
        sendToProxy(player, payload)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        staffChatDisabled.remove(event.getPlayer().getUniqueId())
    }

    override fun onPluginMessageReceived(channel: String, carrier: Player, message: ByteArray) {
        if (ChatBridgeProtocol.CHANNEL != channel) return
        val packet = try {
            ChatBridgeProtocol.decode(message)
        } catch (e: IllegalArgumentException) {
            plugin.getLogger().warning("Ignored malformed chat bridge payload: " + e.message)
            return
        }
        val playerId = packet.playerId()
        if (playerId == null || playerId != carrier.getUniqueId()) {
            plugin.getLogger().warning("Ignored chat bridge payload delivered through the wrong player")
            return
        }
        when (packet.type()) {
            ChatBridgeProtocol.Type.DELIVERY -> deliver(carrier, packet.content()!!)
            ChatBridgeProtocol.Type.STAFF_STATE -> {
                if (packet.enabled()) staffChatDisabled.remove(playerId) else staffChatDisabled.add(playerId)
            }
            else -> plugin.getLogger().warning("Ignored server-bound chat bridge packet of type " + packet.type())
        }
    }

    private fun deliver(player: Player, componentJson: String) {
        val component = try {
            GSON.deserialize(componentJson)
        } catch (e: Exception) {
            plugin.getLogger().warning("Ignored invalid chat component from Velocity")
            return
        }
        runOnMain { if (player.isOnline()) player.sendMessage(component) }
    }

    private fun sendToProxy(player: Player, payload: ByteArray) {
        runOnMain {
            if (!player.isOnline()) return@runOnMain
            try {
                player.sendPluginMessage(plugin, ChatBridgeProtocol.CHANNEL, payload)
            } catch (e: IllegalArgumentException) {
                sendFailure(player, e)
            } catch (e: IllegalStateException) {
                sendFailure(player, e)
            }
        }
    }

    private fun sendFailure(player: Player, exception: RuntimeException) {
        plugin.getLogger().warning("Chat bridge send failed: " + exception.message)
        player.sendMessage(Component.text("Network chat is temporarily unavailable.", NamedTextColor.RED))
    }

    private fun runOnMain(task: Runnable) {
        if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
    }

    companion object {
        private const val STAFF_PERMISSION = "crabutilities.staffchat"
        private val GSON = GsonComponentSerializer.gson()

        private fun sendUsage(player: Player, type: ChatCommandParser.Type) {
            val usage = when (type) {
                ChatCommandParser.Type.PRIVATE -> "Usage: /msg <player> <message>"
                ChatCommandParser.Type.REPLY -> "Usage: /r <message>"
                ChatCommandParser.Type.STAFF -> "Usage: /sc <message>"
                ChatCommandParser.Type.NONE -> ""
            }
            if (usage.isNotEmpty()) player.sendMessage(Component.text(usage, NamedTextColor.RED))
        }
    }
}
