package crabcraft.net.crabUtilities.enderman

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityChangeBlockEvent

/** Stops endermen moving blocks without disabling other mob griefing behaviour. */
open class EndermanGriefListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    open fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (
            event.entity.type == EntityType.ENDERMAN && plugin.config.getBoolean("tweaks.enderman-grief.prevent", false)
        ) {
            event.isCancelled = true
        }
    }
}
