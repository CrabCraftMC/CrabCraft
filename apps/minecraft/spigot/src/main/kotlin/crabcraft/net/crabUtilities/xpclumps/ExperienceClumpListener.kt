package crabcraft.net.crabUtilities.xpclumps

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.entity.Entity
import org.bukkit.entity.ExperienceOrb
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntitySpawnEvent

/** Folds nearby XP orbs into each newly spawned orb. */
class ExperienceClumpListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        if (!plugin.getConfig().getBoolean("tweaks.pvp-clumps.enabled", false)) return
        val spawnedOrb = event.getEntity() as? ExperienceOrb ?: return
        mergeNearbyOrbs(spawnedOrb)
    }
    companion object {
        private const val SEARCH_RADIUS = 3.0
        @JvmStatic
        fun mergeNearbyOrbs(destination: ExperienceOrb) {
            var combinedExperience = destination.getExperience()
            for (entity in destination.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)) {
                val nearbyOrb = entity as? ExperienceOrb ?: continue
                if (nearbyOrb === destination) continue
                combinedExperience += nearbyOrb.getExperience()
                nearbyOrb.remove()
            }
            destination.setExperience(combinedExperience)
        }
    }
}
