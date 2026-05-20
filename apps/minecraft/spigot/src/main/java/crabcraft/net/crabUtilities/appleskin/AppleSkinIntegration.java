package crabcraft.net.crabUtilities.appleskin;

import com.jmatt.appleskinspigot.AppleSkinSpigot;
import com.jmatt.appleskinspigot.listeners.ChannelListener;
import com.jmatt.appleskinspigot.listeners.GameRuleListener;
import com.jmatt.appleskinspigot.util.ServerVersion;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.jetbrains.annotations.NotNull;

/**
 * Initialiser for the in-tree AppleSkin server-side companion. Mirrors the original
 * AppleSkinSpigot's {@code onEnable} but takes the host plugin instance instead of
 * being a {@code JavaPlugin} itself.
 */
public final class AppleSkinIntegration {

    private AppleSkinIntegration() {
    }

    public static void enable(@NotNull JavaPlugin plugin) {
        AppleSkinSpigot.init(plugin);

        final PluginManager pluginManager = plugin.getServer().getPluginManager();
        pluginManager.registerEvents(new ChannelListener(), plugin);

        final Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, AppleSkinSpigot.SATURATION_KEY);
        messenger.registerOutgoingPluginChannel(plugin, AppleSkinSpigot.EXHAUSTION_KEY);

        // The natural_regeneration channel was added in AppleSkin alongside MC 1.21.3.
        // CrabUtilities targets ≥1.21.11 so this is always true, but we keep the check
        // for forward-compatibility with the upstream listener.
        if (ServerVersion.isHigherThanOrEqualTo(1, 21, 3)) {
            messenger.registerOutgoingPluginChannel(plugin, AppleSkinSpigot.NATURAL_REGENERATION_KEY);
            pluginManager.registerEvents(new GameRuleListener(), plugin);
            pluginManager.registerEvents(new GameRuleListener.Paper(), plugin);
        }

        plugin.getLogger().info("AppleSkin integration enabled");
    }
}
