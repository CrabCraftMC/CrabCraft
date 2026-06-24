package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

import java.util.UUID;

/**
 * Enforces each player's {@link PhantomMode} <em>without touching any
 * statistic</em>, so the Night Owl award (longest time since last sleep, backed
 * by {@code time_since_rest}) keeps working.
 *
 * <p>Three event guards, gated by the player's mode:
 * <ul>
 *   <li><b>Spawn</b> ({@link CreatureSpawnEvent}) — a natural phantom spawn is
 *       cancelled only when <em>every</em> player near the spawn has phantoms
 *       {@link PhantomMode#OFF}. If any nearby player allows spawns ({@code ON}
 *       or {@code SAFE}) — or isn't loaded yet — the spawn is left alone, so a
 *       phantom that could belong to someone who wants them is never removed.</li>
 *   <li><b>Target</b> ({@link EntityTargetLivingEntityEvent}) — a phantom is
 *       stopped from acquiring a player whose mode suppresses attacks
 *       ({@code OFF} or {@code SAFE}).</li>
 *   <li><b>Damage</b> ({@link EntityDamageByEntityEvent}) — a final backstop:
 *       any phantom damage to such a player is cancelled, covering a phantom
 *       that was already mid-swoop when the player changed mode.</li>
 * </ul>
 *
 * <p>{@code SAFE} players still have phantoms spawn around them and keep
 * accruing {@code time_since_rest}; they simply can't be attacked.
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
        plugin.getLogger().info("Phantom manager active: per-player phantom modes "
                + (enabled ? "enabled" : "disabled")
                + " (time-since-rest is left untouched, so the Night Owl award is unaffected).");
    }

    /** True only when we positively know the player wants no phantom spawns (OFF). */
    private boolean suppressesSpawn(UUID uuid) {
        return settingsService.isLoaded(uuid) && settingsService.getPhantomMode(uuid).suppressesSpawn();
    }

    /** True only when we positively know the player wants no phantom attacks (OFF or SAFE). */
    private boolean suppressesAttack(UUID uuid) {
        return settingsService.isLoaded(uuid) && settingsService.getPhantomMode(uuid).suppressesAttack();
    }

    /**
     * Cancel a natural phantom spawn when every player near the spawn point has
     * phantoms OFF. Phantoms spawn ~20-34 blocks above their target and within
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

        boolean sawSuppressor = false;
        for (Player player : at.getWorld().getPlayers()) {
            Location loc = player.getLocation();
            double dx = loc.getX() - at.getX();
            double dz = loc.getZ() - at.getZ();
            double dy = Math.abs(loc.getY() - at.getY());
            if (dx * dx + dz * dz <= H_RADIUS_SQ && dy <= V_RADIUS) {
                // Allow the spawn if a nearby player permits spawns (ON or SAFE)
                // or isn't loaded yet, so we never cancel a spawn that might
                // belong to a player who wants phantoms.
                if (!suppressesSpawn(player.getUniqueId())) {
                    return;
                }
                sawSuppressor = true;
            }
        }

        if (sawSuppressor) {
            event.setCancelled(true);
        }
    }

    /** Stop any phantom from acquiring an attack-suppressed player as its target. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPhantomTarget(EntityTargetLivingEntityEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getEntityType() != EntityType.PHANTOM) {
            return;
        }
        if (event.getTarget() instanceof Player target && suppressesAttack(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Backstop: cancel any phantom damage to an attack-suppressed player. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPhantomDamage(EntityDamageByEntityEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getDamager().getType() != EntityType.PHANTOM) {
            return;
        }
        if (event.getEntity() instanceof Player victim && suppressesAttack(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
