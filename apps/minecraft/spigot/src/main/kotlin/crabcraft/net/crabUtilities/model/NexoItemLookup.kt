package crabcraft.net.crabUtilities.model

import org.bukkit.inventory.ItemStack

/** Looks up Nexo item IDs without making the rest of the model feature link against Nexo. */
fun interface NexoItemLookup {
    fun idFromItem(item: ItemStack): String?
}
