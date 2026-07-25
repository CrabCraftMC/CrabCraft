package crabcraft.net.crabUtilities.xaero;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Starts the Xaero map identity integration.
 *
 * <p>Resolves the backend id Xaero clients key their saved maps off: a value
 * of {@code 0} in config means "generate a random id and persist it back" so it
 * stays stable across restarts. Because the config is per-backend, each backend
 * automatically gets distinct map storage when players connect through a proxy.
 */
public final class XaeroBootstrap {

    private XaeroBootstrap() {
    }

    public static void enable(@NotNull CrabUtilities plugin) {
        FileConfiguration config = plugin.getConfig();
        boolean enabled = config.getBoolean("mod-protocols.xaero-map.enabled", true);
        if (!enabled) {
            plugin.getLogger().info("Xaero map integration disabled in config.");
            return;
        }

        int serverId = config.getInt("mod-protocols.xaero-map.server-id", 0);
        if (serverId == 0) {
            serverId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            plugin.saveModuleConfigValues(Map.of(
                    "mod-protocols.xaero-map.server-id",
                    serverId));
        }

        Messenger messenger = plugin.getServer().getMessenger();
        for (String channel : XaeroIntegration.CHANNELS) {
            messenger.registerOutgoingPluginChannel(plugin, channel);
        }
        plugin.getServer().getPluginManager().registerEvents(
                new XaeroIntegration(plugin, serverId), plugin);
        plugin.getServer().getScheduler().runTask(plugin, () -> warnAboutOtherProviders(plugin));

        plugin.getLogger().info("Xaero map integration enabled (server id " + serverId + ")");
    }

    private static void warnAboutOtherProviders(@NotNull CrabUtilities plugin) {
        Messenger messenger = plugin.getServer().getMessenger();
        List<String> otherPlugins = Arrays.stream(plugin.getServer().getPluginManager().getPlugins())
                .filter(candidate -> candidate != plugin)
                .filter(Plugin::isEnabled)
                .filter(candidate -> registersXaeroChannel(messenger, candidate))
                .map(Plugin::getName)
                .sorted()
                .toList();

        if (!otherPlugins.isEmpty()) {
            plugin.getLogger().warning(
                    "Other plugins also send on Xaero map channels: "
                            + String.join(", ", otherPlugins)
                            + ". Disable their server/world ID feature to prevent map identity conflicts.");
        }

        if (hasBuiltInLeavesProtocol(plugin)) {
            plugin.getLogger().warning(
                    "This server includes Leaves' built-in Xaero map protocol. "
                            + "Ensure it is disabled while CrabUtilities provides the server ID.");
        }
    }

    private static boolean registersXaeroChannel(@NotNull Messenger messenger,
                                                 @NotNull Plugin candidate) {
        return XaeroIntegration.CHANNELS.stream()
                .anyMatch(messenger.getOutgoingChannels(candidate)::contains);
    }

    private static boolean hasBuiltInLeavesProtocol(@NotNull CrabUtilities plugin) {
        try {
            Class.forName(
                    "org.leavesmc.leaves.protocol.XaeroMapProtocol",
                    false,
                    plugin.getServer().getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
