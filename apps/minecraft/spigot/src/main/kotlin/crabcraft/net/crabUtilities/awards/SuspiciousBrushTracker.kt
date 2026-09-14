package crabcraft.net.crabUtilities.awards

import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent

/** Records completed archaeology brushes in Minecraft's per-block mined stats. */
class SuspiciousBrushTracker : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val player = event.entity as? Player ?: return
        val brushed = completedBrushMaterial(event.block.type, event.to)
        if (brushed != null) {
            player.incrementStatistic(Statistic.MINE_BLOCK, brushed)
        }
    }

    companion object {
        @JvmStatic
        fun completedBrushMaterial(from: Material, to: Material): Material? {
            if (from == Material.SUSPICIOUS_SAND && to == Material.SAND) return from
            if (from == Material.SUSPICIOUS_GRAVEL && to == Material.GRAVEL) return from
            return null
        }
    }
}
