package crabcraft.net.crabUtilities.endportals

import crabcraft.net.crabUtilities.CrabUtilities
import java.util.function.BooleanSupplier
import org.bukkit.PortalType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPortalEnterEvent

/** Prevents entry through End portals, with configuration read on every event. */
class EndPortalBlockerListener(private val preventEntry: BooleanSupplier) : Listener {
    constructor(plugin: CrabUtilities) : this(BooleanSupplier { plugin.config.getBoolean(CONFIG_PATH, false) })

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityPortalEnter(event: EntityPortalEnterEvent) {
        if (event.portalType == PortalType.ENDER && preventEntry.asBoolean) event.isCancelled = true
    }

    companion object {
        const val CONFIG_PATH = "tweaks.end-portals.prevent-entry"
    }
}
