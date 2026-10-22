package crabcraft.net.crabUtilities.inventory;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class InspectionContainerRegressionTest {

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                .forEach(components -> components.apply());
        usesLiveItemsInEveryEditableSlot();
        exposesTheRealArmourAndOffhandSlots();
        paddingCannotBeExtractedOrPlacedInto();
    }

    private static void usesLiveItemsInEveryEditableSlot() {
        SimpleContainer source = new SimpleContainer(41);
        InspectionContainer view = new InspectionContainer(source, 41);
        check(view.getContainerSize() == 45, "the inspection view is not chest-sized");

        for (int slot = 0; slot < 41; slot++) {
            ItemStack original = new ItemStack(Items.STONE, 4);
            source.setItem(slot, original);
            check(view.getItem(slot) == original, "the view copied source slot " + slot);
            original.shrink(1);
            check(view.getItem(slot).getCount() == 3, "live item changes were hidden in slot " + slot);

            ItemStack replacement = new ItemStack(Items.DIRT, 2);
            view.setItem(slot, replacement);
            check(source.getItem(slot) == replacement, "editing did not update source slot " + slot);
            check(view.getContents().get(slot) == replacement, "inventory snapshots hid source slot " + slot);
            check(view.removeItem(slot, 1).getCount() == 1, "partial extraction failed in slot " + slot);
            check(source.getItem(slot).getCount() == 1, "extraction did not update source slot " + slot);
            check(view.removeItemNoUpdate(slot) == replacement, "full extraction copied source slot " + slot);
            check(source.getItem(slot).isEmpty(), "full extraction left source slot " + slot + " occupied");
        }
    }

    private static void exposesTheRealArmourAndOffhandSlots() {
        EntityEquipment equipment = new EntityEquipment();
        Inventory source = new Inventory(null, equipment);
        InspectionContainer view = new InspectionContainer(source, 41);
        EquipmentSlot[] equipmentSlots = { EquipmentSlot.FEET, EquipmentSlot.LEGS,
                EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.OFFHAND };

        for (int index = 0; index < equipmentSlots.length; index++) {
            ItemStack original = new ItemStack(Items.STONE);
            equipment.set(equipmentSlots[index], original);
            check(view.getItem(36 + index) == original,
                    "equipment slot mapping changed for " + equipmentSlots[index]);
            ItemStack replacement = new ItemStack(Items.DIRT);
            view.setItem(36 + index, replacement);
            check(equipment.get(equipmentSlots[index]) == replacement,
                    "editing did not update real equipment slot " + equipmentSlots[index]);
        }
    }

    private static void paddingCannotBeExtractedOrPlacedInto() {
        SimpleContainer source = new SimpleContainer(41);
        InspectionContainer view = new InspectionContainer(source, 41);
        for (int slot = 41; slot < 45; slot++) {
            ItemStack padding = new ItemStack(Items.GLASS_PANE);
            view.setItem(slot, padding);
            check(view.getItem(slot) == padding, "padding was not retained in display slot " + slot);
            check(!view.canPlaceItem(slot, new ItemStack(Items.DIRT)), "items could be placed in padding slot " + slot);
            check(view.removeItem(slot, 1).isEmpty(), "padding could be partially extracted from slot " + slot);
            check(view.removeItemNoUpdate(slot).isEmpty(), "padding could be extracted from slot " + slot);
        }
        check(source.isEmpty(), "display padding contaminated the player's inventory");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
