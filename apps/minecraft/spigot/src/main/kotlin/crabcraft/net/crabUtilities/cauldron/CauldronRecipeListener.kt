package crabcraft.net.crabUtilities.cauldron

import crabcraft.net.crabUtilities.CrabUtilities
import io.papermc.paper.event.entity.EntityInsideBlockEvent
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack

/** Transforms concrete powder and dirt in water cauldrons without consuming water. */
open class CauldronRecipeListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    open fun onEntityInsideBlock(event: EntityInsideBlockEvent) {
        if (event.block.type != Material.WATER_CAULDRON) return
        val item = event.entity as? Item ?: return
        val stack = item.itemStack
        val result = resultFor(stack.type) ?: return
        item.world.dropItem(item.location, ItemStack(result, stack.amount))
        item.remove()
    }

    private fun resultFor(type: Material): Material? {
        if (
            type.name.endsWith("_CONCRETE_POWDER") &&
                plugin.config.getBoolean("tweaks.cauldron.concrete.enabled", false)
        ) {
            return Material.matchMaterial(type.name.replace("_POWDER", ""))
        }
        if (type in DIRT_TYPES && plugin.config.getBoolean("tweaks.cauldron.mud.enabled", false)) return Material.MUD
        return null
    }

    companion object {
        private val DIRT_TYPES = setOf(Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT)
    }
}
