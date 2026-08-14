package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabMessages;
import crabcraft.net.crabUtilities.CrabUtilities;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends online players a persistent action bar showing their coordinates and
 * facing direction, e.g. "123 64 -78 (NW)".
 *
 * Action bars fade out a few seconds after being sent if not refreshed, so
 * rather than hooking PlayerMoveEvent (which won't fire while a player is
 * standing perfectly still and not even looking around), this runs a small
 * repeating task per online player instead.
 */
public class CoordinateHudManager implements Listener {

    private final CrabUtilities plugin;
    private final PlayerSettingsService settingsService;
    private final Map<UUID, BukkitTask> activeTasks = new ConcurrentHashMap<>();

    private static final long REFRESH_INTERVAL_TICKS = 2L;

    public CoordinateHudManager(CrabUtilities plugin, PlayerSettingsService settingsService) {
        this.plugin = plugin;
        this.settingsService = settingsService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        startTaskFor(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopTaskFor(event.getPlayer());
    }

    /** Call this from your plugin's onEnable() to cover players already online (e.g. after /reload). */
    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::startTaskFor);
    }

    /** Call this from your plugin's onDisable() to clean up all running tasks. */
    public void shutdown() {
        activeTasks.values().forEach(BukkitTask::cancel);
        activeTasks.clear();
    }

    private void startTaskFor(Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stopTaskFor(player);
                return;
            }

            if (!isCoordinateHudEnabled(player)) {
                return;
            }

            sendCoordinateActionBar(player);
        }, 0L, REFRESH_INTERVAL_TICKS);

        activeTasks.put(uuid, task);
    }

    private void stopTaskFor(Player player) {
        BukkitTask task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    private void sendCoordinateActionBar(Player player) {
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        String facing = getFacingDirection(player.getLocation().getYaw());

        Component message = Component.text(x + " " + y + " " + z, CrabMessages.TEXT)
                .append(Component.text(" (" + facing + ")", CrabMessages.MUTED));
        player.sendActionBar(message);
    }

    /**
     * Converts a yaw angle into an 8-point compass direction.
     * Minecraft yaw convention: 0/360 = South, 90 = West, 180 = North, 270 = East.
     */
    private String getFacingDirection(float yaw) {
        float normalizedYaw = (yaw % 360f + 360f) % 360f;
        String[] directions = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        int index = Math.round(normalizedYaw / 45f) % 8;
        return directions[index];
    }

    private boolean isCoordinateHudEnabled(Player player) {
        return settingsService.isCoordinateHudEnabled(player.getUniqueId());
    }
}