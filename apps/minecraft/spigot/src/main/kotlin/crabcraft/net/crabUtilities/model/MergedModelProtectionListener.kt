package crabcraft.net.crabUtilities.model

import crabcraft.net.crabUtilities.CrabMessages
import org.bukkit.block.Crafter
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockCookEvent
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.inventory.PrepareSmithingEvent
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.SmithingInventory

/** Prevents recipes from losing or combining the cosmetic stored inside a merged item. */
class MergedModelProtectionListener(private val codec: MergedModelCodec) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val inventory = event.inventory
        if (shouldClearResult(event.result, inventory.firstItem, inventory.secondItem)) event.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareGrindstone(event: PrepareGrindstoneEvent) {
        val inventory = event.inventory
        if (shouldClearResult(event.result, inventory.upperItem, inventory.lowerItem)) event.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareSmithing(event: PrepareSmithingEvent) {
        val inventory = event.inventory
        if (shouldClearResult(event.result, inventory.inputTemplate, inventory.inputEquipment, inventory.inputMineral)) event.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepareCrafting(event: PrepareItemCraftEvent) {
        if (shouldClearResult(event.inventory.result, *event.inventory.matrix)) event.inventory.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockCook(event: BlockCookEvent) {
        if (codec.isMerged(event.source) && !codec.preservesMerge(event.source, event.result)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCrafterCraft(event: CrafterCraftEvent) {
        val crafter = event.block.state as? Crafter
        if (crafter != null && shouldClearResult(event.result, *crafter.inventory.contents)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onResultClick(event: InventoryClickEvent) {
        if (event.slotType != InventoryType.SlotType.RESULT
            || event.clickedInventory != event.view.topInventory || !hasUnsafeResult(event.view.topInventory)) return
        event.isCancelled = true
        event.whoClicked.sendMessage(CrabMessages.error("Split the merged helmet first."))
    }

    private fun hasUnsafeResult(inventory: Inventory): Boolean = when (inventory) {
        is AnvilInventory -> shouldClearResult(inventory.result, inventory.firstItem, inventory.secondItem)
        is GrindstoneInventory -> shouldClearResult(inventory.result, inventory.upperItem, inventory.lowerItem)
        is SmithingInventory -> shouldClearResult(inventory.result, inventory.inputTemplate, inventory.inputEquipment, inventory.inputMineral)
        is CraftingInventory -> shouldClearResult(inventory.result, *inventory.matrix)
        else -> false
    }

    private fun shouldClearResult(result: ItemStack?, vararg inputs: ItemStack?): Boolean {
        var mergedInput: ItemStack? = null
        for (input in inputs) {
            if (!codec.isMerged(input)) continue
            if (mergedInput != null) return true
            mergedInput = input
        }
        return mergedInput != null && !codec.preservesMerge(mergedInput, result)
    }
}
