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
 * per player with {@link Attribute#WAYPOINT_RECEIVE_RANGE}. All players keep a
 * high {@link Attribute#WAYPOINT_TRANSMIT_RANGE}, so opted-in players can see
 * everyone while opted-out players do not receive locator bar waypoints.
 */
public class LocatorBarManager implements Listener {

    private static final double ENABLED_RANGE = 60_000_000.0D;
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
        plugin.getLogger().info("Locator bar manager active: world gamerule enabled, players opt in to viewing via /settings.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        enableLocatorBarGameRule(player.getWorld());

        // Default to not receiving waypoints immediately; the async settings
        // load will re-apply the saved opt-in through the settings listener.
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
        setAttribute(player, Attribute.WAYPOINT_TRANSMIT_RANGE, ENABLED_RANGE);
        setAttribute(player, Attribute.WAYPOINT_RECEIVE_RANGE, enabled ? ENABLED_RANGE : DISABLED_RECEIVE_RANGE);
        if (!enabled) {
            keepTransmittingWaypoint(player);
        }
    }

    private void setAttribute(Player player, Attribute attribute, double value) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }

        if (Double.compare(instance.getBaseValue(), value) != 0) {
            instance.setBaseValue(value);
        }
    }

    private void keepTransmittingWaypoint(Player player) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        ServerWaypointManager waypointManager = handle.level().getWaypointManager();

        // Setting receive range to 0 removes the player as a receiver and also
        // untracks their waypoint. Re-track it so opted-in viewers can still
        // see players who have their own locator bar disabled.
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
