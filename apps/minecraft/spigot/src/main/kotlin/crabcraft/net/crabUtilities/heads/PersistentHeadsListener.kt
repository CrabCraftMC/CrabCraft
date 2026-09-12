package crabcraft.net.crabUtilities.heads

import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.Skull
import org.bukkit.block.TileState
import org.bukkit.entity.Item
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.jspecify.annotations.Nullable
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom

/** Preserves named or lore-bearing heads across placement, breaking, water and explosions. */
open class PersistentHeadsListener(private val plugin: CrabUtilities) : Listener {
    private val headItemKey = NamespacedKey(plugin, "head_item")
    private fun isEnabled(): Boolean = plugin.getConfig().getBoolean("tweaks.persistent-heads.enabled", false)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onBlockPlace(event: BlockPlaceEvent) {
        if (!isEnabled()) return
        val hand = event.getItemInHand()
        if (hand.getType() != Material.PLAYER_HEAD) return
        val meta = hand.getItemMeta() ?: return
        if (!meta.hasDisplayName() && !meta.hasLore()) return
        val state = event.getBlockPlaced().getState(true) as? TileState ?: return
        val encoded = Base64.getEncoder().encodeToString(hand.asOne().serializeAsBytes())
        state.getPersistentDataContainer().set(headItemKey, PersistentDataType.STRING, encoded)
        if (state.isSnapshot) state.update()
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onBlockDropItem(event: BlockDropItemEvent) {
        if (!isEnabled()) return
        val type = event.getBlockState().getType()
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) return
        val state = event.getBlockState() as? TileState ?: return
        val stored = readStored(state) ?: return
        for (item in event.getItems()) if (item.getItemStack().getType() == Material.PLAYER_HEAD) item.setItemStack(stored.asOne())
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onPlayerBucketEmpty(event: PlayerBucketEmptyEvent) { if (isEnabled()) handleBlock(event.getBlock(), event, false) }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onLiquidFlow(event: BlockFromToEvent) { if (isEnabled()) handleBlock(event.getToBlock(), event, true) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onBlockExplode(event: BlockExplodeEvent) { if (isEnabled()) handleExplosion(event.blockList(), event.getYield()) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onEntityExplode(event: EntityExplodeEvent) { if (isEnabled()) handleExplosion(event.blockList(), event.getYield()) }
    private fun handleExplosion(blocks: MutableList<Block>, yield: Float) {
        val iterator = blocks.iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            if (isPlayerHead(block.getType()) && ThreadLocalRandom.current().nextFloat() <= yield) {
                handleBlock(block, null, false)
                iterator.remove()
            }
        }
    }
    private fun handleBlock(block: Block, event: Cancellable?, cancelEvent: Boolean) {
        // Liquid spread is frequent, so avoid a BlockState allocation for non-heads.
        if (!isPlayerHead(block.getType())) return
        val skull = block.getState() as? Skull ?: return
        val stored = readStored(skull) ?: return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { block.getWorld().dropItemNaturally(block.getLocation(), stored.asOne()) }, 1L)
        block.setType(Material.AIR)
        if (cancelEvent && event != null) event.setCancelled(true)
    }
    private fun readStored(state: TileState): ItemStack? {
        val encoded = state.getPersistentDataContainer().get(headItemKey, PersistentDataType.STRING) ?: return null
        return try { ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)) } catch (ex: RuntimeException) {
            plugin.getLogger().warning("Failed to read stored head data: " + ex.message)
            null
        }
    }
    companion object {
        private fun isPlayerHead(type: Material): Boolean = type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD
    }
}
