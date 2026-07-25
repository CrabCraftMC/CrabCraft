package crabcraft.net.crabUtilities.chat.bridge;

import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.chatbridge.ChatBridgeProtocol;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets Paper own local chat processing while Velocity remains authoritative
 * for cross-server routing and state.
 */
public final class PaperChatBridge implements Listener, PluginMessageListener {

    private static final String STAFF_PERMISSION = "crabutilities.staffchat";
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private final CrabUtilities plugin;
    private final Set<UUID> staffChatDisabled = ConcurrentHashMap.newKeySet();

    public PaperChatBridge(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(
                plugin, ChatBridgeProtocol.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, ChatBridgeProtocol.CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin, ChatBridgeProtocol.CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(
                plugin, ChatBridgeProtocol.CHANNEL);
        staffChatDisabled.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        ChatCommandParser.Parsed command = ChatCommandParser.parse(event.getMessage());
        if (!command.recognised()) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!command.valid()) {
            sendUsage(player, command.type());
            return;
        }

        try {
            switch (command.type()) {
                case PRIVATE -> sendToProxy(player,
                        ChatBridgeProtocol.privateRequest(command.target(), command.message()));
                case REPLY -> sendToProxy(player,
                        ChatBridgeProtocol.replyRequest(command.message()));
                case STAFF -> {
                    if (!player.hasPermission(STAFF_PERMISSION)) {
                        player.sendMessage(Component.text(
                                "You do not have permission to use staff chat.", NamedTextColor.RED));
                        return;
                    }
                    sendToProxy(player, ChatBridgeProtocol.staffRequest(
                            GSON.serialize(Component.text(command.message()))));
                }
                case NONE -> {
                    // Handled by the early return above.
                }
            }
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text("That chat message is too large.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStaffPrefix(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(STAFF_PERMISSION)
                || staffChatDisabled.contains(player.getUniqueId())
                || !StaffChatComponents.hasPrefix(event.message())) {
            return;
        }

        Component staffMessage = StaffChatComponents.removePrefix(event.message());
        if (StaffChatComponents.isEmpty(staffMessage)) return;

        // Never let a failed staff-chat bridge leak a staff message into public chat.
        event.setCancelled(true);
        byte[] payload;
        try {
            payload = ChatBridgeProtocol.staffRequest(GSON.serialize(staffMessage));
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text("That staff message is too large.", NamedTextColor.RED));
            return;
        }
        sendToProxy(player, payload);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        staffChatDisabled.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!ChatBridgeProtocol.CHANNEL.equals(channel)) return;

        ChatBridgeProtocol.Packet packet;
        try {
            packet = ChatBridgeProtocol.decode(message);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Ignored malformed chat bridge payload: " + e.getMessage());
            return;
        }

        UUID playerId = packet.playerId();
        if (playerId == null || !playerId.equals(carrier.getUniqueId())) {
            plugin.getLogger().warning("Ignored chat bridge payload delivered through the wrong player");
            return;
        }

        switch (packet.type()) {
            case DELIVERY -> deliver(carrier, packet.content());
            case STAFF_STATE -> {
                if (packet.enabled()) {
                    staffChatDisabled.remove(playerId);
                } else {
                    staffChatDisabled.add(playerId);
                }
            }
            default -> plugin.getLogger().warning(
                    "Ignored server-bound chat bridge packet of type " + packet.type());
        }
    }

    private void deliver(Player player, String componentJson) {
        Component component;
        try {
            component = GSON.deserialize(componentJson);
        } catch (Exception e) {
            plugin.getLogger().warning("Ignored invalid chat component from Velocity");
            return;
        }

        runOnMain(() -> {
            if (player.isOnline()) player.sendMessage(component);
        });
    }

    private void sendToProxy(Player player, byte[] payload) {
        runOnMain(() -> {
            if (!player.isOnline()) return;
            try {
                player.sendPluginMessage(plugin, ChatBridgeProtocol.CHANNEL, payload);
            } catch (IllegalArgumentException | IllegalStateException e) {
                plugin.getLogger().warning("Chat bridge send failed: " + e.getMessage());
                player.sendMessage(Component.text(
                        "Network chat is temporarily unavailable.", NamedTextColor.RED));
            }
        });
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private static void sendUsage(Player player, ChatCommandParser.Type type) {
        String usage = switch (type) {
            case PRIVATE -> "Usage: /msg <player> <message>";
            case REPLY -> "Usage: /r <message>";
            case STAFF -> "Usage: /sc <message>";
            case NONE -> "";
        };
        if (!usage.isEmpty()) {
            player.sendMessage(Component.text(usage, NamedTextColor.RED));
        }
    }
}
