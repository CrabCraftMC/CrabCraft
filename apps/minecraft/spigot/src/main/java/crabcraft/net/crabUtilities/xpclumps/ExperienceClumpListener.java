package crabcraft.net.crabUtilities.xpclumps;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;

/** Reduces XP entity counts by folding nearby orbs into each newly spawned orb. */
public final class ExperienceClumpListener implements Listener {
    private static final double SEARCH_RADIUS = 3D;

    private final CrabUtilities plugin;

    public ExperienceClumpListener(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!plugin.getConfig().getBoolean("tweaks.pvp-clumps.enabled", false)) {
            return;
        }
        if (event.getEntity() instanceof ExperienceOrb spawnedOrb) {
            mergeNearbyOrbs(spawnedOrb);
        }
    }

    static void mergeNearbyOrbs(ExperienceOrb destination) {
        int combinedExperience = destination.getExperience();
        for (Entity entity : destination.getNearbyEntities(
                SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)) {
            if (!(entity instanceof ExperienceOrb nearbyOrb) || nearbyOrb == destination) {
                continue;
            }
            combinedExperience += nearbyOrb.getExperience();
            nearbyOrb.remove();
        }
        destination.setExperience(combinedExperience);
    }
}
