package crabcraft.net.crabUtilities.velocity.messaging;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import crabcraft.net.crabUtilities.chatbridge.ChatBridgeProtocol;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.Optional;

/**
 * Routes Paper-processed chat requests through Velocity and returns final
 * components to the recipient's active Paper server.
 */
public final class VelocityChatBridge {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(ChatBridgeProtocol.CHANNEL);
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final CrabUtilitiesVelocity plugin;

    public VelocityChatBridge(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getChannelRegistrar().register(CHANNEL);
        plugin.getServer().getEventManager().register(plugin, this);
    }

    public void shutdown() {
        plugin.getServer().getEventManager().unregisterListener(plugin, this);
        plugin.getServer().getChannelRegistrar().unregister(CHANNEL);
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection source)
                || !(event.getTarget() instanceof Player player)
                || player.getCurrentServer().filter(source::equals).isEmpty()) {
            plugin.getLogger().warn("Ignored chat bridge payload without a matching backend player");
            return;
        }

        ChatBridgeProtocol.Packet packet;
        try {
            packet = ChatBridgeProtocol.decode(event.getData());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warn("Ignored malformed chat bridge payload: {}", e.getMessage());
            return;
        }

        plugin.getServer().getScheduler().buildTask(plugin, () -> handle(player, packet)).schedule();
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        StaffChatManager manager = plugin.getStaffChatManager();
        boolean enabled = manager == null
                || manager.isEnabled(event.getPlayer().getUniqueId());
        syncStaffState(event.getPlayer(), enabled);
    }

    public boolean deliver(Player player, Component component) {
        byte[] payload;
        try {
            payload = ChatBridgeProtocol.delivery(player.getUniqueId(), GSON.serialize(component));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warn("Chat component for {} was too large for the Paper bridge",
                    player.getUsername());
            return false;
        }

        Optional<ServerConnection> connection = player.getCurrentServer();
        return connection.isPresent() && connection.get().sendPluginMessage(CHANNEL, payload);
    }

    public void syncStaffState(Player player, boolean enabled) {
        Optional<ServerConnection> connection = player.getCurrentServer();
        if (connection.isEmpty()) return;
        connection.get().sendPluginMessage(
                CHANNEL, ChatBridgeProtocol.staffState(player.getUniqueId(), enabled));
    }

    private void handle(Player player, ChatBridgeProtocol.Packet packet) {
        switch (packet.type()) {
            case PRIVATE_REQUEST ->
                    plugin.getMessageManager().sendToName(
                            player, packet.target(), Component.text(packet.content()));
            case REPLY_REQUEST ->
                    plugin.getMessageManager().reply(player, Component.text(packet.content()));
            case STAFF_REQUEST -> handleStaffRequest(player, packet.content());
            default -> plugin.getLogger().warn(
                    "Ignored proxy-bound chat bridge packet of type {}", packet.type());
        }
    }

    private void handleStaffRequest(Player player, String componentJson) {
        if (!plugin.getStaffChatManager().hasPermission(player)) {
            plugin.getMessageManager().deliver(player,
                    Component.text(
                            "You do not have permission to use staff chat.",
                            NamedTextColor.RED));
            return;
        }

        Component message;
        try {
            message = GSON.deserialize(componentJson);
        } catch (Exception e) {
            plugin.getLogger().warn("Ignored invalid staff-chat component from {}", player.getUsername());
            return;
        }

        String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
        String senderName = raw != null ? raw : player.getUsername();
        plugin.getStaffChatManager().sendMessage(
                senderName, player.getUniqueId(), message);
    }
}
