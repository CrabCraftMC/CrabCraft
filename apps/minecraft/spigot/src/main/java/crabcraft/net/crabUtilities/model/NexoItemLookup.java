package crabcraft.net.crabUtilities.model;

import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Looks up Nexo item IDs without making the rest of the model feature link against Nexo. */
@FunctionalInterface
interface NexoItemLookup {
    @Nullable String idFromItem(ItemStack item);
}
