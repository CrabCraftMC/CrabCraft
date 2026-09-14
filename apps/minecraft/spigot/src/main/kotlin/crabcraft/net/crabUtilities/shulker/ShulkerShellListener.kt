package crabcraft.net.crabUtilities.shulker

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Material
import org.bukkit.entity.Shulker
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack

/** Multiplies shells after the vanilla drop roll, preserving its chance to drop nothing. */
open class ShulkerShellListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    open fun onEntityDeath(event: EntityDeathEvent) {
        val shulker = event.getEntity() as? Shulker ?: return
        if (!plugin.getConfig().getBoolean("tweaks.shulker-shells.enabled", false)) return
        val configured = if (shulker.getKiller() != null) plugin.getConfig().getInt("tweaks.shulker-shells.player-kill-multiplier", 2)
            else plugin.getConfig().getInt("tweaks.shulker-shells.other-multiplier", 1)
        val multiplier = maxOf(1, configured)
        if (multiplier == 1) return
        for (drop in event.getDrops()) if (drop.getType() == Material.SHULKER_SHELL) drop.setAmount(drop.getAmount() * multiplier)
    }
}
