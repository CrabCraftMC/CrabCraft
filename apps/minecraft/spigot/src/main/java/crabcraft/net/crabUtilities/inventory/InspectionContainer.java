package crabcraft.net.crabUtilities.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** A chest-sized view over the real inventory, including its equipment slots. */
final class InspectionContainer extends SimpleContainer {
    private final Container source;
    private final int editableSlots;

    InspectionContainer(Container source, int editableSlots) {
        super(45);
        this.source = source;
        this.editableSlots = editableSlots;
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot < editableSlots ? source.getItem(slot) : super.getItem(slot);
    }

    @Override
    public void setItem(int slot, ItemStack item) {
        if (slot < editableSlots) source.setItem(slot, item);
        else super.setItem(slot, item);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        return slot < editableSlots ? source.removeItem(slot, count) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return slot < editableSlots ? source.removeItemNoUpdate(slot) : ItemStack.EMPTY;
    }

    @Override
    public void setChanged() {
        if (source != null) source.setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack item) {
        return slot < editableSlots;
    }

    @Override
    public List<ItemStack> getContents() {
        List<ItemStack> items = new ArrayList<>(getContainerSize());
        for (int slot = 0; slot < getContainerSize(); slot++) items.add(getItem(slot));
        return items;
    }
}
