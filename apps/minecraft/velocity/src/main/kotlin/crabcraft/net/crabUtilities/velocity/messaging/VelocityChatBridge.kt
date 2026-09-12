package crabcraft.net.crabUtilities.velocity.messaging

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import crabcraft.net.crabUtilities.chatbridge.ChatBridgeProtocol
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer

/** Routes Paper-processed chat requests and returns final components to the active Paper server. */
class VelocityChatBridge(private val plugin: CrabUtilitiesVelocity) {
    fun start() {
        plugin.getServer().channelRegistrar.register(CHANNEL)
        plugin.getServer().eventManager.register(plugin, this)
    }
    fun shutdown() {
        plugin.getServer().eventManager.unregisterListener(plugin, this)
        plugin.getServer().channelRegistrar.unregister(CHANNEL)
    }
    @Subscribe(order = PostOrder.EARLY)
    fun onPluginMessage(event: PluginMessageEvent) {
        if (CHANNEL != event.identifier) return
        event.result = PluginMessageEvent.ForwardResult.handled()
        val source = event.source as? ServerConnection
        val player = event.target as? Player
        if (source == null || player == null || player.currentServer.filter(source::equals).isEmpty) {
            plugin.getLogger().warn("Ignored chat bridge payload without a matching backend player"); return
        }
        val packet = try { ChatBridgeProtocol.decode(event.data) }
        catch (e: IllegalArgumentException) { plugin.getLogger().warn("Ignored malformed chat bridge payload: {}", e.message); return }
        plugin.getServer().scheduler.buildTask(plugin, Runnable { handle(player, packet) }).schedule()
    }
    @Subscribe fun onServerPostConnect(event: ServerPostConnectEvent) {
        val manager = plugin.getStaffChatManager()
        val enabled = manager.isEnabled(event.player.uniqueId)
        syncStaffState(event.player, enabled)
    }
    fun deliver(player: Player, component: Component): Boolean {
        val payload = try { ChatBridgeProtocol.delivery(player.uniqueId, GSON.serialize(component)) }
        catch (_: IllegalArgumentException) { plugin.getLogger().warn("Chat component for {} was too large for the Paper bridge", player.username); return false }
        val connection = player.currentServer
        return connection.isPresent && connection.get().sendPluginMessage(CHANNEL, payload)
    }
    fun syncStaffState(player: Player, enabled: Boolean) {
        val connection = player.currentServer
        if (connection.isEmpty) return
        connection.get().sendPluginMessage(CHANNEL, ChatBridgeProtocol.staffState(player.uniqueId, enabled))
    }
    private fun handle(player: Player, packet: ChatBridgeProtocol.Packet) {
        when (packet.type()) {
            ChatBridgeProtocol.Type.PRIVATE_REQUEST -> plugin.getMessageManager().sendToName(player, packet.target()!!, Component.text(packet.content()!!))
            ChatBridgeProtocol.Type.REPLY_REQUEST -> plugin.getMessageManager().reply(player, Component.text(packet.content()!!))
            ChatBridgeProtocol.Type.STAFF_REQUEST -> handleStaffRequest(player, packet.content()!!)
            else -> plugin.getLogger().warn("Ignored proxy-bound chat bridge packet of type {}", packet.type())
        }
    }
    private fun handleStaffRequest(player: Player, componentJson: String) {
        if (!plugin.getStaffChatManager().hasPermission(player)) {
            plugin.getMessageManager().deliver(player, Component.text("You do not have permission to use staff chat.", NamedTextColor.RED)); return
        }
        val message = try { GSON.deserialize(componentJson) }
        catch (_: Exception) { plugin.getLogger().warn("Ignored invalid staff-chat component from {}", player.username); return }
        val raw = plugin.getNicknameCache().getRawNickname(player.uniqueId)
        val senderName = raw ?: player.username
        plugin.getStaffChatManager().sendMessage(senderName, player.uniqueId, message)
    }
    companion object {
        private val CHANNEL = MinecraftChannelIdentifier.from(ChatBridgeProtocol.CHANNEL)
        private val GSON = GsonComponentSerializer.gson()
    }
}
