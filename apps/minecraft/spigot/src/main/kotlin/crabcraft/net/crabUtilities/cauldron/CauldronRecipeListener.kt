package crabcraft.net.crabUtilities.cauldron

import crabcraft.net.crabUtilities.CrabUtilities
import io.papermc.paper.event.entity.EntityInsideBlockEvent
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.jspecify.annotations.Nullable

/** Converts concrete powder and dirt dropped into water cauldrons when enabled. */
open class CauldronRecipeListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    open fun onEntityInsideBlock(event: EntityInsideBlockEvent) {
        if (event.getBlock().getType() != Material.WATER_CAULDRON) return
        val item = event.getEntity() as? Item ?: return
        val stack = item.getItemStack()
        val result = resultFor(stack.getType()) ?: return
        item.getWorld().dropItem(item.getLocation(), ItemStack(result, stack.getAmount()))
        item.remove()
    }
    private fun resultFor(type: Material): Material? {
        if (isConcretePowder(type) && plugin.getConfig().getBoolean("tweaks.cauldron.concrete.enabled", false)) {
            return Material.matchMaterial(type.name.replace("_POWDER", ""))
        }
        if (DIRT_TYPES.contains(type) && plugin.getConfig().getBoolean("tweaks.cauldron.mud.enabled", false)) return Material.MUD
        return null
    }
    companion object {
        private val DIRT_TYPES = setOf(Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT)
        private fun isConcretePowder(type: Material): Boolean = type.name.endsWith("_CONCRETE_POWDER")
    }
}
