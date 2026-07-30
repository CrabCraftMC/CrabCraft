package crabcraft.net.crabUtilities.slime;

import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Handles interactions with slime-map features. */
public final class SlimeMapListener implements Listener {

    @EventHandler
    public void onSlimeBallUse(PlayerInteractEvent event) {
        Material itemType = event.getItem() == null ? Material.AIR : event.getItem().getType();
        Material mainHandType = event.getPlayer().getInventory().getItemInMainHand().getType();
        if (!shouldOpenMap(event.getAction(), event.getHand(), itemType, mainHandType)) {
            return;
        }

        SlimeCommand.openMap(event.getPlayer());
    }

    static boolean shouldOpenMap(Action action, EquipmentSlot hand, Material itemType, Material mainHandType) {
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean duplicateOffHandEvent = hand == EquipmentSlot.OFF_HAND && mainHandType == Material.SLIME_BALL;
        return rightClick && itemType == Material.SLIME_BALL && !duplicateOffHandEvent;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SlimeMap) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SlimeMap) {
            event.setCancelled(true);
        }
    }
}
