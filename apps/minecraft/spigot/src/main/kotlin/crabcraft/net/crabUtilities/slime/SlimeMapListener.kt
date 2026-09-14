package crabcraft.net.crabUtilities.slime

import org.bukkit.Material
import org.bukkit.event.block.Action
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class SlimeMapListener : Listener {
    @EventHandler
    fun onSlimeBallUse(event: PlayerInteractEvent) {
        val itemType = event.getItem()?.getType() ?: Material.AIR
        val mainHandType = event.getPlayer().getInventory().getItemInMainHand().getType()
        if (!shouldOpenMap(event.getAction(), event.getHand(), itemType, mainHandType)) return
        SlimeCommand.openMap(event.getPlayer())
    }
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.getView().getTopInventory().getHolder() is SlimeMap) event.setCancelled(true)
    }
    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.getView().getTopInventory().getHolder() is SlimeMap) event.setCancelled(true)
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
