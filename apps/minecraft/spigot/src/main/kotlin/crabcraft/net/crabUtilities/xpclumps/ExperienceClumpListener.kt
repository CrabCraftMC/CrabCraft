package crabcraft.net.crabUtilities.xpclumps

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.entity.ExperienceOrb
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntitySpawnEvent

/** Reduces XP entity counts by folding nearby orbs into each newly spawned orb. */
class ExperienceClumpListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        if (!plugin.config.getBoolean("tweaks.pvp-clumps.enabled", false)) return
        (event.entity as? ExperienceOrb)?.let(::mergeNearbyOrbs)
    }

    companion object {
        private const val SEARCH_RADIUS = 3.0

        @JvmStatic
        fun mergeNearbyOrbs(destination: ExperienceOrb) {
            var combinedExperience = destination.experience
            for (entity in destination.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)) {
                if (entity !is ExperienceOrb || entity === destination) continue
                combinedExperience += entity.experience
                entity.remove()
            }
            destination.experience = combinedExperience
        }
    }
}
