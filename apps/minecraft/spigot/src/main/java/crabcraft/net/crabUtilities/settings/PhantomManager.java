package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Enforces each player's phantom preference.
 *
 * <p>Vanilla phantom spawning is strictly per-player: every spawn attempt the
 * game iterates over players and rolls a spawn only for those whose
 * {@code time_since_rest} statistic has reached 72000 ticks (3 in-game days).
 * To keep phantoms away from a player who has them disabled we simply hold
 * that player's {@code time_since_rest} below the threshold by resetting it to
 * zero on a short interval — exactly what a bed does. This is precise (only
 * the opted-out player is affected; nearby players who want phantoms keep
 * their own independent counter) and side-effect free (the statistic only
 * feeds the phantom mechanic).
 *
 * <p>Players who have phantoms <em>enabled</em> are left completely alone, so
 * vanilla behaviour applies to them.
 *
 * <p>As a secondary guard (configurable, on by default) we also cancel any
 * natural phantom spawn whose nearby players have all opted out — this covers
 * the brief window between a player crossing the threshold and the next reset
 * sweep. It is deliberately conservative: if any player near the spawn wants
 * phantoms, the spawn is allowed.
 *
 * <p>Statistic writes happen on the main thread (the reset sweep is a sync
 * task and the update hook is dispatched on the main thread by
 * {@link PlayerSettingsService}). On Folia these would need to run on each
 * entity's region thread; this plugin targets Paper.
 */
public class PhantomManager implements Listener {

    /**
     * Below the vanilla 72000-tick insomnia threshold there is no spawn, so
     * resetting to 0 well within an hour of real time is always sufficient.
     */
    private static final int RESET_VALUE = 0;

    private final CrabUtilities plugin;
    private final PlayerSettingsService settingsService;
    private final long resetIntervalTicks;
    private final boolean cancelNaturalSpawns;

    private BukkitTask resetTask;

    public PhantomManager(CrabUtilities plugin, PlayerSettingsService settingsService) {
        this.plugin = plugin;
        this.settingsService = settingsService;
        // Clamp both ends: a floor avoids a busy loop, and a ceiling well under
        // the 72000-tick (3600s) insomnia threshold guarantees the reset always
        // lands before a player could become phantom-eligible.
        long intervalSeconds = Math.min(1800L, Math.max(5L,
                plugin.getConfig().getLong("phantoms.reset-interval-seconds", 30L)));
        this.resetIntervalTicks = intervalSeconds * 20L;
        this.cancelNaturalSpawns = plugin.getConfig().getBoolean("phantoms.cancel-natural-spawns", true);
    }

    public void start() {
        // Periodic sweep keeps opted-out players' insomnia counter pinned low.
        this.resetTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::sweep, resetIntervalTicks, resetIntervalTicks);
        plugin.getLogger().info("Phantom manager started: resetting time-since-rest every "
                + (resetIntervalTicks / 20L) + "s for players with phantoms off"
                + (cancelNaturalSpawns ? "; natural-spawn guard enabled." : "."));
    }

    public void shutdown() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
    }

    /** Resets the insomnia counter for every online player who has phantoms off. */
    private void sweep() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            // Only act on players whose settings are known. A not-yet-loaded
            // player is handled by apply() the instant their record resolves,
            // so we never reset a player who may actually want phantoms.
            if (settingsService.isLoaded(uuid) && !settingsService.isPhantomsEnabled(uuid)) {
                resetInsomnia(player);
            }
        }
    }

    /**
     * Applies a player's current preference immediately. Called on the main
     * thread by {@link PlayerSettingsService} when settings are loaded on join
     * or toggled, so disabling phantoms takes effect at once rather than on the
     * next sweep. Enabling is a no-op (the counter then accrues naturally).
     */
    public void apply(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!settingsService.isPhantomsEnabled(uuid)) {
            resetInsomnia(player);
        }
    }

    private void resetInsomnia(Player player) {
        try {
            if (player.getStatistic(Statistic.TIME_SINCE_REST) > RESET_VALUE) {
                player.setStatistic(Statistic.TIME_SINCE_REST, RESET_VALUE);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not reset time-since-rest for " + player.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Secondary guard: cancel a natural phantom spawn unless some player near
     * the spawn point actually wants phantoms. The primary reset mechanism
     * already stops opted-out players from triggering spawns, so this mostly
     * catches the gap before the first sweep after a player crosses the
     * threshold. Conservative by design — never cancels when a nearby player
     * has phantoms enabled.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!cancelNaturalSpawns) {
            return;
        }
        if (event.getEntityType() != EntityType.PHANTOM) {
            return;
        }
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }

        Location at = event.getLocation();
        if (at.getWorld() == null) {
            return;
        }

        // Phantoms spawn ~20-34 blocks above their target and within ~10 blocks
        // horizontally, so look for the player they were spawned for nearby.
        boolean sawOptedOut = false;
        for (Player player : at.getWorld().getPlayers()) {
            Location loc = player.getLocation();
            double dx = loc.getX() - at.getX();
            double dz = loc.getZ() - at.getZ();
            double dy = Math.abs(loc.getY() - at.getY());
            if (dx * dx + dz * dz <= 16.0 * 16.0 && dy <= 48.0) {
                UUID uuid = player.getUniqueId();
                // Treat "wants phantoms" OR "not yet loaded" as a reason to
                // allow the spawn, so we never cancel a spawn that might belong
                // to a player who actually enabled phantoms.
                if (!settingsService.isLoaded(uuid) || settingsService.isPhantomsEnabled(uuid)) {
                    return;
                }
                sawOptedOut = true;
            }
        }

        if (sawOptedOut) {
            event.setCancelled(true);
        }
    }
}
