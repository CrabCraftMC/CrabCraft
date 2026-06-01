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

/**
 * Static holder + initialiser for the in-tree Jade server-side companion.
 *
 * <p>The original jade-paper port carried a {@code JadePaperPlugin} {@link JavaPlugin}
 * that owned the {@code INSTANCE}/{@code LOGGER} statics and ran the messenger
 * registration in {@code onEnable}. In CrabUtilities we fold that work into this
 * class so the bigger plugin can drive the lifecycle.
 */
public final class JadeBootstrap {

    public static JavaPlugin INSTANCE;
    public static Logger LOGGER;

    private JadeBootstrap() {
    }

    public static void enable(@NotNull JavaPlugin plugin) {
        INSTANCE = plugin;
        LOGGER = plugin.getSLF4JLogger();

        JadeProtocol.init();

        JadeMessenger.registerIncoming(plugin, ClientHandshakePayload.class, JadeProtocol::clientHandshake);
        JadeMessenger.registerIncoming(plugin, RequestBlockPayload.class, JadeProtocol::requestBlockData);
        JadeMessenger.registerIncoming(plugin, RequestEntityPayload.class, JadeProtocol::requestEntityData);

        JadeMessenger.registerOutgoing(plugin, ServerHandshakePayload.class);
        JadeMessenger.registerOutgoing(plugin, ReceiveDataPayload.class);

        JadeIntegration integration = new JadeIntegration();
        plugin.getServer().getPluginManager().registerEvents(integration, plugin);
        var command = plugin.getCommand("jadehandshake");
        if (command != null) command.setExecutor(integration);

        plugin.getLogger().info("Jade integration enabled (protocol v" + JadeProtocol.PROTOCOL_VERSION + ")");
    }
}
