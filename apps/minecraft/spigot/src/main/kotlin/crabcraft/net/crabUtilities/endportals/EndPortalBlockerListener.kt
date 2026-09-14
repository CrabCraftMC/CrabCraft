package crabcraft.net.crabUtilities.endportals

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.PortalType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPortalEnterEvent
import java.util.function.BooleanSupplier

/** Prevents End portal entry in either direction while the live toggle is enabled. */
class EndPortalBlockerListener(private val preventEntry: BooleanSupplier) : Listener {
    constructor(plugin: CrabUtilities) : this(BooleanSupplier { plugin.getConfig().getBoolean(CONFIG_PATH, false) })
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityPortalEnter(event: EntityPortalEnterEvent) {
        if (event.getPortalType() == PortalType.ENDER && preventEntry.asBoolean) event.setCancelled(true)
    }
    companion object { const val CONFIG_PATH = "tweaks.end-portals.prevent-entry" }
}
