package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

import java.util.UUID;

/**
 * Enforces each player's phantom preference <em>without touching any
 * statistic</em>.
 *
 * <p>Phantoms are kept away from opted-out players purely through events:
 * <ul>
 *   <li>a natural phantom spawn whose nearby players have all opted out is
 *       cancelled, so phantoms never appear for them; and</li>
 *   <li>any phantom that tries to target an opted-out player has that target
 *       cancelled, so a phantom spawned for someone else can't harass them.</li>
 * </ul>
 *
 * <p><b>Why not reset {@code time_since_rest}?</b> Vanilla gates phantom
 * spawning on each player's {@code time_since_rest} statistic, so zeroing it is
 * the most precise per-player off-switch — but that exact statistic also backs
 * the <em>Night Owl</em> award ("longest time since last sleep",
 * {@code minecraft:custom/minecraft:time_since_rest}). Resetting it would peg
 * that award at zero for every opted-out player (i.e. everyone, since phantoms
 * default OFF), making it unwinnable. Cancelling spawns/targets instead leaves
 * the statistic — and the award — completely intact.
 *
 * <p>Players who have phantoms enabled are left entirely to vanilla behaviour.
 * The spawn guard is conservative: a spawn is only cancelled when every nearby
 * player has opted out, so a phantom that could belong to a player who wants
 * them is never removed.
 */
public class PhantomManager implements Listener {

    private static final double H_RADIUS_SQ = 16.0 * 16.0;
    private static final double V_RADIUS = 48.0;

    private final CrabUtilities plugin;
    private final PlayerSettingsService settingsService;
    private final boolean enabled;

    public PhantomManager(CrabUtilities plugin, PlayerSettingsService settingsService) {
        this.plugin = plugin;
        this.settingsService = settingsService;
        this.enabled = plugin.getConfig().getBoolean("phantoms.suppress-for-opted-out", true);
    }

    public void start() {
        plugin.getLogger().info("Phantom manager active: per-player phantom suppression "
                + (enabled ? "enabled" : "disabled")
                + " (time-since-rest is left untouched, so the Night Owl award is unaffected).");
    }

    /** True only when we positively know the player has phantoms turned off. */
    private boolean optedOut(UUID uuid) {
        return settingsService.isLoaded(uuid) && !settingsService.isPhantomsEnabled(uuid);
    }

    /**
     * Cancel a natural phantom spawn when every player near the spawn point has
     * opted out. Phantoms spawn ~20-34 blocks above their target and within
     * ~10 blocks horizontally, so the target is found within this search box.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!enabled) {
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

        boolean sawOptedOut = false;
        for (Player player : at.getWorld().getPlayers()) {
            Location loc = player.getLocation();
            double dx = loc.getX() - at.getX();
            double dz = loc.getZ() - at.getZ();
            double dy = Math.abs(loc.getY() - at.getY());
            if (dx * dx + dz * dz <= H_RADIUS_SQ && dy <= V_RADIUS) {
                UUID uuid = player.getUniqueId();
                // Allow the spawn if a nearby player wants phantoms — or whose
                // preference isn't loaded yet — so we never cancel a spawn that
                // might belong to a player who enabled phantoms.
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

    /** Stop any phantom from acquiring an opted-out player as its target. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPhantomTarget(EntityTargetLivingEntityEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getEntityType() != EntityType.PHANTOM) {
            return;
        }
        if (event.getTarget() instanceof Player target && optedOut(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
