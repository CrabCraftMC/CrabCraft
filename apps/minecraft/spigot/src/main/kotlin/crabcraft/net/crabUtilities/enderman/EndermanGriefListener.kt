package crabcraft.net.crabUtilities.enderman

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent

/** Stops endermen from picking up or placing blocks when the live toggle is enabled. */
open class EndermanGriefListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    open fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.getEntity().getType() != EntityType.ENDERMAN) return
        if (plugin.getConfig().getBoolean("tweaks.enderman-grief.prevent", false)) event.setCancelled(true)
    }
}
