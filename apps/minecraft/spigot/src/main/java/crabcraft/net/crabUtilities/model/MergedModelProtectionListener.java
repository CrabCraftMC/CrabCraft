package crabcraft.net.crabUtilities.model;

import crabcraft.net.crabUtilities.CrabMessages;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;

/** Prevents recipes from losing or combining the cosmetic stored inside a merged item. */
final class MergedModelProtectionListener implements Listener {

    private final MergedModelCodec codec;

    MergedModelProtectionListener(MergedModelCodec codec) {
        this.codec = codec;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        var inventory = event.getInventory();
        if (shouldClearResult(
                event.getResult(),
                inventory.getFirstItem(),
                inventory.getSecondItem())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        var inventory = event.getInventory();
        if (shouldClearResult(
                event.getResult(),
                inventory.getUpperItem(),
                inventory.getLowerItem())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        var inventory = event.getInventory();
        if (shouldClearResult(
                event.getResult(),
                inventory.getInputTemplate(),
                inventory.getInputEquipment(),
                inventory.getInputMineral())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCrafting(PrepareItemCraftEvent event) {
        if (shouldClearResult(event.getInventory().getResult(), event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockCook(BlockCookEvent event) {
        if (codec.isMerged(event.getSource())
                && !codec.preservesMerge(event.getSource(), event.getResult())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof Crafter crafter
                && shouldClearResult(event.getResult(), crafter.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResultClick(InventoryClickEvent event) {
        if (event.getSlotType() != InventoryType.SlotType.RESULT
                || event.getClickedInventory() != event.getView().getTopInventory()
                || !hasUnsafeResult(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        event.getWhoClicked().sendMessage(CrabMessages.error("Split the merged helmet first."));
    }

    private boolean hasUnsafeResult(Inventory inventory) {
        if (inventory instanceof AnvilInventory anvil) {
            return shouldClearResult(
                    anvil.getResult(), anvil.getFirstItem(), anvil.getSecondItem());
        }
        if (inventory instanceof GrindstoneInventory grindstone) {
            return shouldClearResult(
                    grindstone.getResult(), grindstone.getUpperItem(), grindstone.getLowerItem());
        }
        if (inventory instanceof SmithingInventory smithing) {
            return shouldClearResult(
                    smithing.getResult(),
                    smithing.getInputTemplate(),
                    smithing.getInputEquipment(),
                    smithing.getInputMineral());
        }
        if (inventory instanceof CraftingInventory crafting) {
            return shouldClearResult(crafting.getResult(), crafting.getMatrix());
        }
        return false;
    }

    private boolean shouldClearResult(ItemStack result, ItemStack... inputs) {
        ItemStack mergedInput = null;
        for (ItemStack input : inputs) {
            if (!codec.isMerged(input)) {
                continue;
            }
            if (mergedInput != null) {
                return true;
            }
            mergedInput = input;
        }

        return mergedInput != null && !codec.preservesMerge(mergedInput, result);
    }
}
