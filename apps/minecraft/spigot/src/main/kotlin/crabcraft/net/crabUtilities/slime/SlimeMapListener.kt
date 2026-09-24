package crabcraft.net.crabUtilities.slime

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class SlimeMapListener : Listener {
    @EventHandler
    fun onSlimeBallUse(event: PlayerInteractEvent) {
        val itemType = event.item?.type ?: Material.AIR
        val mainHandType = event.player.inventory.itemInMainHand.type
        if (shouldOpenMap(event.action, event.hand, itemType, mainHandType)) SlimeCommand.openMap(event.player)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder is SlimeMap) event.isCancelled = true
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is SlimeMap) event.isCancelled = true
    }

    companion object {
        @JvmStatic
        fun shouldOpenMap(action: Action, hand: EquipmentSlot?, itemType: Material, mainHandType: Material): Boolean {
            val rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK
            val duplicateOffHandEvent = hand == EquipmentSlot.OFF_HAND && mainHandType == Material.SLIME_BALL
            return rightClick && itemType == Material.SLIME_BALL && !duplicateOffHandEvent
        }
    }
}
