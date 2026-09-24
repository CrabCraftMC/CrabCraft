package crabcraft.net.crabUtilities.heads

import crabcraft.net.crabUtilities.CrabUtilities
import java.util.Base64
import java.util.concurrent.ThreadLocalRandom
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.Skull
import org.bukkit.block.TileState
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
import org.bukkit.persistence.PersistentDataType

/** Preserves a placed head's name, lore and texture using its serialised item bytes. */
open class PersistentHeadsListener(private val plugin: CrabUtilities) : Listener {
    private val headItemKey = NamespacedKey(plugin, "head_item")

    private fun isEnabled() = plugin.config.getBoolean("tweaks.persistent-heads.enabled", false)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onBlockPlace(event: BlockPlaceEvent) {
        if (!isEnabled()) return
        val hand = event.itemInHand
        if (hand.type != Material.PLAYER_HEAD) return
        val meta = hand.itemMeta ?: return
        if (!meta.hasDisplayName() && !meta.hasLore()) return
        val state = event.blockPlaced.getState(true) as? TileState ?: return
        val encoded = Base64.getEncoder().encodeToString(hand.asOne().serializeAsBytes())
        state.persistentDataContainer.set(headItemKey, PersistentDataType.STRING, encoded)
        if (state.isSnapshot) state.update()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onBlockDropItem(event: BlockDropItemEvent) {
        if (!isEnabled() || !isPlayerHead(event.blockState.type)) return
        val state = event.blockState as? TileState ?: return
        val stored = readStored(state) ?: return
        for (item in event.items) if (item.itemStack.type == Material.PLAYER_HEAD) item.itemStack = stored.asOne()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onPlayerBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (isEnabled()) handleBlock(event.block, event, false)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onLiquidFlow(event: BlockFromToEvent) {
        if (isEnabled()) handleBlock(event.toBlock, event, true)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onBlockExplode(event: BlockExplodeEvent) {
        if (isEnabled()) handleExplosion(event.blockList(), event.yield)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    open fun onEntityExplode(event: EntityExplodeEvent) {
        if (isEnabled()) handleExplosion(event.blockList(), event.yield)
    }

    private fun handleExplosion(blocks: MutableList<Block>, yield: Float) {
        val iterator = blocks.iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            if (isPlayerHead(block.type) && ThreadLocalRandom.current().nextFloat() <= yield) {
                handleBlock(block, null, false)
                iterator.remove()
            }
        }
    }

    private fun handleBlock(block: Block, event: Cancellable?, cancelEvent: Boolean) {
        // Avoid allocating block state snapshots on the common non-head liquid path.
        if (!isPlayerHead(block.type)) return
        val skull = block.state as? Skull ?: return
        val stored = readStored(skull) ?: return
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                Runnable {
                    block.world.dropItemNaturally(block.location, stored.asOne())
                },
                1L,
            )
        block.type = Material.AIR
        if (cancelEvent && event != null) event.isCancelled = true
    }

    private fun isPlayerHead(type: Material) = type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD

    private fun readStored(state: TileState): ItemStack? {
        val encoded = state.persistentDataContainer.get(headItemKey, PersistentDataType.STRING) ?: return null
        return try {
            ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded))
        } catch (ex: RuntimeException) {
            plugin.logger.warning("Failed to read stored head data: ${ex.message}")
            null
        }
    }
}
