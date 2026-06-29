package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabUtilities;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.GameRuleKeys;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.UUID;

/**
 * Per-player opt-in for Minecraft's locator bar.
 *
 * <p>The vanilla waypoint manager is gated by the world-level {@code locatorBar}
 * gamerule, so this feature keeps that gamerule enabled and controls visibility
 * per player with {@link Attribute#WAYPOINT_RECEIVE_RANGE}. A receive range of
 * {@code 0} means the player receives no locator bar waypoints; restoring the
 * attribute default gives them vanilla behaviour.
 */
public class LocatorBarManager implements Listener {

    private static final double DISABLED_RECEIVE_RANGE = 0.0D;

    private final CrabUtilities plugin;
    private final PlayerSettingsService settingsService;

    public LocatorBarManager(CrabUtilities plugin, PlayerSettingsService settingsService) {
        this.plugin = plugin;
        this.settingsService = settingsService;
        this.settingsService.addListener(this::onSettingsChanged);
    }

    public void start() {
        for (World world : Bukkit.getWorlds()) {
            enableLocatorBarGameRule(world);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player, false);
        }
        plugin.getLogger().info("Locator bar manager active: world gamerule enabled, players opt in via /settings.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        enableLocatorBarGameRule(player.getWorld());

        // Default to hidden immediately; the async settings load will re-apply
        // the saved opt-in through the settings listener.
        apply(player, false);

        UUID uuid = player.getUniqueId();
        if (settingsService.isLoaded(uuid)) {
            apply(player, settingsService.isLocatorBarEnabled(uuid));
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        enableLocatorBarGameRule(player.getWorld());

        UUID uuid = player.getUniqueId();
        apply(player, settingsService.isLoaded(uuid) && settingsService.isLocatorBarEnabled(uuid));
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        enableLocatorBarGameRule(event.getWorld());
    }

    private void onSettingsChanged(UUID uuid, PlayerSettings settings) {
        Runnable task = () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                enableLocatorBarGameRule(player.getWorld());
                apply(player, settings.isLocatorBar());
            }
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void apply(Player player, boolean enabled) {
        AttributeInstance receiveRange = player.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (receiveRange == null) {
            return;
        }

        double value = enabled ? receiveRange.getDefaultValue() : DISABLED_RECEIVE_RANGE;
        if (Double.compare(receiveRange.getBaseValue(), value) != 0) {
            receiveRange.setBaseValue(value);
        }
        if (!enabled) {
            keepTransmittingWaypoint(player);
        }
    }

    private void keepTransmittingWaypoint(Player player) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        ServerWaypointManager waypointManager = handle.level().getWaypointManager();

        // ServerWaypointManager.removePlayer() is triggered when receive range
        // becomes 0, and it also untracks the player as a waypoint transmitter.
        // Re-track the player so opted-in viewers can still see normal players.
        waypointManager.untrackWaypoint(handle);
        if (handle.isTransmittingWaypoint()) {
            waypointManager.trackWaypoint(handle);
        }
    }

    private void enableLocatorBarGameRule(World world) {
        GameRule<Boolean> locatorBar = locatorBarGameRule();
        if (!Boolean.TRUE.equals(world.getGameRuleValue(locatorBar))) {
            world.setGameRule(locatorBar, true);
        }
    }

    @SuppressWarnings("unchecked")
    private GameRule<Boolean> locatorBarGameRule() {
        return (GameRule<Boolean>) RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.GAME_RULE)
                .getOrThrow(GameRuleKeys.LOCATOR_BAR);
    }
}
