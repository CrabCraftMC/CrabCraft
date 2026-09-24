package crabcraft.net.crabUtilities.bingo

import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack

/** Shared tracking primitives; task ownership and lifetime rules stay with each detector. */
object BingoTracking {
    const val MAX_TRANSIENT_ENTRIES = 4_096

    @JvmStatic
    fun <K, V> putBounded(map: MutableMap<K, V>, key: K, value: V) {
        if (!map.containsKey(key) && map.size >= MAX_TRANSIENT_ENTRIES) map.remove(map.keys.first())
        map[key] = value
    }

    @JvmStatic
    fun setOwnedBlock(
        key: BlockKey,
        playerId: UUID,
        owners: MutableMap<BlockKey, UUID>,
        blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>,
        maximum: Int,
    ) {
        val previous = owners.put(key, playerId)
        if (previous != null && previous != playerId) removeFromOwnerIndex(blocksByPlayer, previous, key)
        val blocks = blocksByPlayer.getOrPut(playerId) { LinkedHashSet() }
        blocks.add(key)
        while (blocks.size > maximum) {
            val oldest = blocks.first()
            blocks.remove(oldest)
            owners.remove(oldest, playerId)
        }
    }

    @JvmStatic
    fun removeOwnedBlock(
        key: BlockKey,
        owners: MutableMap<BlockKey, UUID>,
        blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>,
    ): UUID? {
        val owner = owners.remove(key)
        if (owner != null) removeFromOwnerIndex(blocksByPlayer, owner, key)
        return owner
    }

    @JvmStatic
    fun removeOwnedBlocks(
        playerId: UUID,
        owners: MutableMap<BlockKey, UUID>,
        blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>,
    ) {
        val blocks = blocksByPlayer.remove(playerId) ?: return
        for (block in blocks) owners.remove(block, playerId)
    }

    @JvmStatic
    fun removeFromOwnerIndex(
        blocksByPlayer: MutableMap<UUID, MutableSet<BlockKey>>,
        playerId: UUID,
        key: BlockKey,
    ) {
        val blocks = blocksByPlayer[playerId] ?: return
        blocks.remove(key)
        if (blocks.isEmpty()) blocksByPlayer.remove(playerId)
    }

    @JvmStatic
    fun isFresh(earlier: Int, current: Int, maximumAge: Int): Boolean {
        val age = current - earlier
        return age >= 0 && age <= maximumAge
    }

    @JvmStatic
    fun sameWorld(first: Location, second: Location): Boolean = first.world != null && first.world == second.world

    @JvmStatic fun singleItem(stack: ItemStack): ItemStack = stack.clone().also { it.amount = 1 }

    @JvmStatic
    fun blockAt(key: BlockKey): Block? = Bukkit.getWorld(key.worldId())?.getBlockAt(key.x(), key.y(), key.z())

    data class BlockKey(private val worldId: UUID, private val x: Int, private val y: Int, private val z: Int) {
        fun worldId(): UUID = worldId

        fun x(): Int = x

        fun y(): Int = y

        fun z(): Int = z

        fun relative(face: BlockFace) = BlockKey(worldId, x + face.modX, y + face.modY, z + face.modZ)

        companion object {
            @JvmStatic fun from(block: Block) = BlockKey(block.world.uid, block.x, block.y, block.z)

            @JvmStatic
            fun from(location: Location) =
                BlockKey(location.world!!.uid, location.blockX, location.blockY, location.blockZ)
        }
    }
}
