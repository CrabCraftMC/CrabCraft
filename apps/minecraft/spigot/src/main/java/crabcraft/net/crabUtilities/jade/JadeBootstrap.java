package crabcraft.net.crabUtilities.jade;

import crabcraft.net.crabUtilities.CrabMessages;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.command.CommandExecutor;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol;
import crabcraft.net.crabUtilities.jade.protocol.payload.ClientHandshakePayload;
import crabcraft.net.crabUtilities.jade.protocol.payload.ReceiveDataPayload;
import crabcraft.net.crabUtilities.jade.protocol.payload.RequestBlockPayload;
import crabcraft.net.crabUtilities.jade.protocol.payload.RequestEntityPayload;
import crabcraft.net.crabUtilities.jade.protocol.payload.ServerHandshakePayload;
import crabcraft.net.crabUtilities.jade.protocol.JadeMessenger;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * Static holder + initialiser for the in-tree Jade server-side companion.
 *
 * <p>The original jade-paper port carried a {@code JadePaperPlugin} {@link JavaPlugin}
 * that owned the {@code INSTANCE}/{@code LOGGER} statics and ran the messenger
 * registration in {@code onEnable}. In CrabUtilities we fold that work into this
 * class so the bigger plugin can drive the lifecycle.
 */
public final class JadeBootstrap {

    private static final String CLIENT_PROTOCOL_CHANNEL = "crabcraft:client_protocol";

    public static JavaPlugin INSTANCE;
    public static Logger LOGGER;
    private static JadeIntegration integration;
    private static PluginMessageListener clientProtocolListener;
    private static Set<ServerPlayer> validatedPlayers = Set.of();
    private static boolean enabled;
    private static final CommandExecutor DISABLED_COMMAND = (sender, command, label, args) -> {
        sender.sendMessage(CrabMessages.error("Jade integration is disabled."));
        return true;
    };

    private JadeBootstrap() {
    }

    public static synchronized boolean enable(@NotNull JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("mod-protocols.jade.enabled", true)) {
            disable(plugin);
            plugin.getLogger().info("Jade integration disabled in config.");
            return false;
        }
        if (enabled) {
            return true;
        }

        INSTANCE = plugin;
        LOGGER = plugin.getSLF4JLogger();

        try {
            boolean inventoryDataEnabled = plugin.getConfig().getBoolean(
                    "mod-protocols.jade.inventory-data-enabled", false);
            JadeProtocol.init(inventoryDataEnabled);
            if (!inventoryDataEnabled) {
                plugin.getLogger().info(
                        "Jade inventory data disabled; protection-aware access checks are unavailable.");
            }

            JadeMessenger.registerIncoming(
                    plugin,
                    ClientHandshakePayload.class,
                    JadeProtocol::clientHandshake);
            JadeMessenger.registerIncoming(
                    plugin,
                    RequestBlockPayload.class,
                    JadeProtocol::requestBlockData);
            JadeMessenger.registerIncoming(
                    plugin,
                    RequestEntityPayload.class,
                    JadeProtocol::requestEntityData);

            JadeMessenger.registerOutgoing(plugin, ServerHandshakePayload.class);
            JadeMessenger.registerOutgoing(plugin, ReceiveDataPayload.class);

            clientProtocolListener = (channel, player, data) -> {
                int protocol = decodeClientProtocol(data);
                if (protocol > 0) {
                    JadeProtocol.setClientProtocol(player.getUniqueId(), protocol);
                }
            };
            plugin.getServer().getMessenger().registerIncomingPluginChannel(
                    plugin,
                    CLIENT_PROTOCOL_CHANNEL,
                    clientProtocolListener);

            integration = new JadeIntegration();
            plugin.getServer().getPluginManager().registerEvents(integration, plugin);
            var command = plugin.getCommand("jadehandshake");
            if (command != null) {
                command.setExecutor(integration);
            }

            enabled = true;
            for (var player : plugin.getServer().getOnlinePlayers()) {
                ServerPlayer serverPlayer = ((CraftPlayer) player).getHandle();
                if (!validatedPlayers.contains(serverPlayer)) {
                    continue;
                }
                try {
                    JadeProtocol.resendHandshake(serverPlayer);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning(
                            "Could not resend the Jade handshake to "
                                    + player.getName()
                                    + ": "
                                    + exception.getMessage());
                }
            }
            validatedPlayers = Set.of();
            plugin.getLogger().info(
                    "Jade integration enabled (protocol v"
                            + JadeProtocol.PROTOCOL_VERSION
                            + ")");
            return true;
        } catch (LinkageError | RuntimeException exception) {
            disable(plugin);
            plugin.getLogger().warning(
                    "Jade integration could not initialise: " + exception.getMessage());
            return false;
        }
    }

    public static synchronized void disable(@NotNull JavaPlugin plugin) {
        if (enabled) {
            validatedPlayers = JadeProtocol.snapshotEnabledPlayers();
        }
        enabled = false;
        JadeProtocol.shutdown();

        if (integration != null) {
            HandlerList.unregisterAll(integration);
            integration = null;
        }

        JadeMessenger.unregisterIncoming(plugin, ClientHandshakePayload.class);
        JadeMessenger.unregisterIncoming(plugin, RequestBlockPayload.class);
        JadeMessenger.unregisterIncoming(plugin, RequestEntityPayload.class);
        JadeMessenger.unregisterOutgoing(plugin, ServerHandshakePayload.class);
        JadeMessenger.unregisterOutgoing(plugin, ReceiveDataPayload.class);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(
                plugin,
                CLIENT_PROTOCOL_CHANNEL);
        clientProtocolListener = null;

        var command = plugin.getCommand("jadehandshake");
        if (command != null) {
            command.setExecutor(DISABLED_COMMAND);
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    static int decodeClientProtocol(byte[] data) {
        if (data.length != Integer.BYTES) {
            return -1;
        }
        int protocol = ByteBuffer.wrap(data).getInt();
        return protocol > 0 ? protocol : -1;
    }
}
