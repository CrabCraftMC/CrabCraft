package crabcraft.net.crabUtilities.xaero;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Static initialiser for the in-tree Xaero map server-side companion.
 *
 * <p>Resolves the per-world id Xaero clients key their saved maps off: a value
 * of {@code 0} in config means "generate a random id and persist it back" so it
 * stays stable across restarts. Because the config is per-backend, each backend
 * auto-gets a distinct id, giving every world its own Xaero map storage.
 */
public final class XaeroBootstrap {

    private XaeroBootstrap() {
    }

    public static void enable(@NotNull JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        boolean enabled = config.getBoolean("mod-protocols.xaero-map.enabled", true);

        int serverId = config.getInt("mod-protocols.xaero-map.server-id", 0);
        if (serverId == 0) {
            // First start with no id configured: pick a stable random one and
            // persist it so it survives restarts. saveConfig keeps the on-disk
            // comments intact (parseComments defaults to true on modern Paper).
            serverId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            config.set("mod-protocols.xaero-map.server-id", serverId);
            plugin.saveConfig();
        }

        XaeroMapProtocol.configure(enabled, serverId);

        if (!enabled) {
            plugin.getLogger().info("Xaero map integration disabled in config.");
            return;
        }

        plugin.getServer().getPluginManager().registerEvents(new XaeroIntegration(), plugin);

        // Payloads go out over the NMS connection, but register the channels with
        // Bukkit's messenger too for bookkeeping (mirrors the Jade port).
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, XaeroMapProtocol.idMini("main").toString());
        messenger.registerOutgoingPluginChannel(plugin, XaeroMapProtocol.idWorld("main").toString());

        plugin.getLogger().info("Xaero map integration enabled (server id " + serverId + ")");
    }
}
