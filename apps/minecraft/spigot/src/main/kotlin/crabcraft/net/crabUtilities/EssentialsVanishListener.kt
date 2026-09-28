package crabcraft.net.crabUtilities

import net.ess3.api.events.VanishStatusChangeEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/** Loads the EssentialsX vanish event only when the soft dependency is present. */
class EssentialsVanishListener(
    private val plugin: CrabUtilities,
    private val publisher: VanishStatusPublisher,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVanishChange(event: VanishStatusChangeEvent) {
        val player = event.affected.base ?: return
        if (!player.isOnline) return
        // Send the committed value before refreshing other derived state.
        publisher.publish(player, event.value)
        Bukkit.getScheduler()
            .runTask(
                plugin,
                Runnable {
                    publisher.publish(player)
                    plugin.onVanishStatusChanged(player)
                },
            )
    }
}
