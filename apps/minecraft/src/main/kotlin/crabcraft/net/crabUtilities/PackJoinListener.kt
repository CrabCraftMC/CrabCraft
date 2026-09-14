package crabcraft.net.crabUtilities

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

open class PackJoinListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    open fun onJoin(event: PlayerJoinEvent) {
        plugin.getResourcePackManager().sendIfOptedIn(event.player)
    }
}
