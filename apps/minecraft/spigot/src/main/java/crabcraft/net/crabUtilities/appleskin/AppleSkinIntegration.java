package crabcraft.net.crabUtilities.appleskin;

import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

public final class AppleSkinIntegration implements Listener {

    static final String SATURATION_CHANNEL = "appleskin:saturation";
    static final String EXHAUSTION_CHANNEL = "appleskin:exhaustion";
    private static final String NATURAL_REGENERATION_CHANNEL =
            "appleskin:natural_regeneration";
    private static final List<String> CHANNELS = List.of(
            SATURATION_CHANNEL,
            EXHAUSTION_CHANNEL,
            NATURAL_REGENERATION_CHANNEL);

    private static @Nullable JavaPlugin plugin;
    private static @Nullable AppleSkinIntegration listener;
    private static volatile boolean enabled;
    private static volatile long generation;

    private AppleSkinIntegration() {
    }

    public static synchronized boolean enable(@NotNull JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("mod-protocols.appleskin.enabled", true)) {
            disable(plugin);
            plugin.getLogger().info("AppleSkin integration disabled in config.");
            return false;
        }
        if (enabled) {
            return true;
        }

        AppleSkinIntegration.plugin = plugin;
        generation++;

        try {
            listener = new AppleSkinIntegration();
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);

            final Messenger messenger = plugin.getServer().getMessenger();
            CHANNELS.forEach(channel ->
                    messenger.registerOutgoingPluginChannel(plugin, channel));

            enabled = true;
            plugin.getServer().getOnlinePlayers().forEach(player -> {
                if (player.getListeningPluginChannels().contains(SATURATION_CHANNEL)) {
                    new AppleSkinSyncTask(player);
                }
                if (player.getListeningPluginChannels().contains(NATURAL_REGENERATION_CHANNEL)) {
                    syncNaturalRegeneration(player);
                }
            });
            plugin.getLogger().info("AppleSkin integration enabled");
            return true;
        } catch (LinkageError | RuntimeException exception) {
            disable(plugin);
            plugin.getLogger().warning(
                    "AppleSkin integration could not initialise: " + exception.getMessage());
            return false;
        }
    }

    public static synchronized void disable(@NotNull JavaPlugin plugin) {
        invalidateTasks();

        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        final Messenger messenger = plugin.getServer().getMessenger();
        CHANNELS.forEach(channel ->
                messenger.unregisterOutgoingPluginChannel(plugin, channel));
    }

    public static boolean isEnabled() {
        return enabled;
    }

    static long currentGeneration() {
        return generation;
    }

    static boolean isCurrentGeneration(long expected) {
        return enabled && generation == expected;
    }

    static JavaPlugin plugin() {
        return Objects.requireNonNull(plugin, "AppleSkin integration is not enabled");
    }

    static void invalidateTasks() {
        enabled = false;
        generation++;
        plugin = null;
    }

    @EventHandler
    private void onPlayerRegisterChannel(final PlayerRegisterChannelEvent event) {
        if (event.getChannel().equals(SATURATION_CHANNEL)) {
            new AppleSkinSyncTask(event.getPlayer());
        } else if (event.getChannel().equals(NATURAL_REGENERATION_CHANNEL)) {
            syncNaturalRegeneration(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void onPlayerChangedWorld(final PlayerChangedWorldEvent event) {
        if (event.getPlayer().getListeningPluginChannels()
                .contains(NATURAL_REGENERATION_CHANNEL)) {
            syncNaturalRegeneration(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void onGameRuleChange(final WorldGameRuleChangeEvent event) {
        if (event.getGameRule() != naturalRegenerationRule()) {
            return;
        }
        event.getWorld().getPlayers().forEach(player -> {
            if (player.getListeningPluginChannels().contains(NATURAL_REGENERATION_CHANNEL)) {
                sendNaturalRegeneration(
                        player,
                        Boolean.parseBoolean(event.getValue()));
            }
        });
    }

    private static void syncNaturalRegeneration(Player player) {
        sendNaturalRegeneration(
                player,
                Boolean.TRUE.equals(
                        player.getWorld().getGameRuleValue(naturalRegenerationRule())));
    }

    private static void sendNaturalRegeneration(Player player, boolean enabled) {
        player.sendPluginMessage(
                plugin(),
                NATURAL_REGENERATION_CHANNEL,
                ByteBuffer.allocate(1).put((byte) (enabled ? 1 : 0)).array());
    }

    private static GameRule<Boolean> naturalRegenerationRule() {
        return GameRules.NATURAL_HEALTH_REGENERATION;
    }
}
