package crabcraft.net.crabUtilities.jade;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;
import org.leavesmc.leaves.protocol.jade.payload.ClientHandshakePayload;
import org.leavesmc.leaves.protocol.jade.payload.ReceiveDataPayload;
import org.leavesmc.leaves.protocol.jade.payload.RequestBlockPayload;
import org.leavesmc.leaves.protocol.jade.payload.RequestEntityPayload;
import org.leavesmc.leaves.protocol.jade.payload.ServerHandshakePayload;
import org.jadepaper.JadeMessenger;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

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

    private JadeBootstrap() {
    }

    public static void enable(@NotNull JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("mod-protocols.jade.enabled", true)) {
            plugin.getLogger().info("Jade integration disabled in config.");
            return;
        }

        INSTANCE = plugin;
        LOGGER = plugin.getSLF4JLogger();

        JadeProtocol.init();

        JadeMessenger.registerIncoming(plugin, ClientHandshakePayload.class, JadeProtocol::clientHandshake);
        JadeMessenger.registerIncoming(plugin, RequestBlockPayload.class, JadeProtocol::requestBlockData);
        JadeMessenger.registerIncoming(plugin, RequestEntityPayload.class, JadeProtocol::requestEntityData);

        JadeMessenger.registerOutgoing(plugin, ServerHandshakePayload.class);
        JadeMessenger.registerOutgoing(plugin, ReceiveDataPayload.class);

        plugin.getServer().getMessenger().registerIncomingPluginChannel(
                plugin, CLIENT_PROTOCOL_CHANNEL, (channel, player, data) -> {
                    int protocol = decodeClientProtocol(data);
                    if (protocol > 0) {
                        JadeProtocol.setClientProtocol(player.getUniqueId(), protocol);
                    }
                });

        JadeIntegration integration = new JadeIntegration();
        plugin.getServer().getPluginManager().registerEvents(integration, plugin);
        var command = plugin.getCommand("jadehandshake");
        if (command != null) command.setExecutor(integration);

        plugin.getLogger().info("Jade integration enabled (protocol v" + JadeProtocol.PROTOCOL_VERSION + ")");
    }

    static int decodeClientProtocol(byte[] data) {
        if (data.length != Integer.BYTES) {
            return -1;
        }
        int protocol = ByteBuffer.wrap(data).getInt();
        return protocol > 0 ? protocol : -1;
    }
}
