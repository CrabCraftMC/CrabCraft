package crabcraft.net.crabUtilities.shulker

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Material
import org.bukkit.entity.Shulker
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent

/** Multiplies shells that actually dropped, preserving the vanilla drop chance. */
open class ShulkerShellListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    open fun onEntityDeath(event: EntityDeathEvent) {
        val shulker = event.entity as? Shulker ?: return
        if (!plugin.config.getBoolean("tweaks.shulker-shells.enabled", false)) return
        val configured =
            if (shulker.killer != null) plugin.config.getInt("tweaks.shulker-shells.player-kill-multiplier", 2)
            else plugin.config.getInt("tweaks.shulker-shells.other-multiplier", 1)
        val multiplier = maxOf(1, configured)
        if (multiplier == 1) return
        for (drop in event.drops) {
            if (drop.type == Material.SHULKER_SHELL) drop.amount *= multiplier
        }
    }
}
