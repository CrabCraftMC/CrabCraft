package crabcraft.net.crabUtilities.netherportals

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Orientable
import java.util.ArrayDeque
import java.util.Optional
import java.util.UUID

/** Stores custom portal membership and reconstructs it without vanilla's 21-block cap. */
class CustomPortalRegistry {
    private val portalsByWorld = HashMap<UUID, MutableMap<Long, CustomPortalBounds>>()

    fun register(world: World, axis: PortalAxis, portalBlocks: Collection<Block>) {
        val positions = portalBlocks.map(::positionOf)
        register(world, CustomPortalBounds(axis, positions))
    }

    fun findOrDiscover(seed: Block): Optional<CustomPortalBounds> {
        val axis = axisOf(seed) ?: return Optional.empty()
        val portals = portalsByWorld[seed.world.uid]
        val cached = portals?.get(blockKey(seed.x, seed.y, seed.z))
        if (cached != null && cached.axis() == axis && cached.contains(positionOf(seed))) return Optional.of(cached)
        return discover(seed, axis).map { bounds -> register(seed.world, bounds); bounds }
    }

    fun findPortalsTouchingFrame(frame: Block): List<CustomPortalBounds> {
        val found = ArrayList<CustomPortalBounds>()
        for (face in FRAME_NEIGHBOURS) {
            val adjacent = frame.getRelative(face)
            val axis = axisOf(adjacent)
            if (axis == null || !axis.isInPlane(face)) continue
            val adjacentPosition = positionOf(adjacent)
            if (found.any { it.contains(adjacentPosition) }) continue
            discover(adjacent, axis).ifPresent { bounds -> register(frame.world, bounds); found.add(bounds) }
        }
        return java.util.List.copyOf(found)
    }

    fun unregister(world: World, bounds: CustomPortalBounds) {
        val portals = portalsByWorld[world.uid] ?: return
        remove(portals, bounds)
        if (portals.isEmpty()) portalsByWorld.remove(world.uid)
    }

    fun forget(world: World, block: CustomPortalBounds.BlockPosition) {
        val portals = portalsByWorld[world.uid] ?: return
        portals.remove(blockKey(block.x(), block.y(), block.z()))
        if (portals.isEmpty()) portalsByWorld.remove(world.uid)
    }

    private fun discover(seed: Block, axis: PortalAxis): Optional<CustomPortalBounds> {
        val pending = ArrayDeque<Block>()
        val checked = HashSet<CustomPortalBounds.BlockPosition>()
        val portalBlocks = ArrayList<CustomPortalBounds.BlockPosition>()
        pending.add(seed)
        while (!pending.isEmpty()) {
            val block = pending.removeFirst()
            val position = positionOf(block)
            if (!checked.add(position) || axisOf(block) != axis) continue
            portalBlocks.add(position)
            if (portalBlocks.size > MAX_PORTAL_BLOCKS) return Optional.empty()
            pending.addLast(block.getRelative(BlockFace.UP))
            pending.addLast(block.getRelative(BlockFace.DOWN))
            pending.addLast(block.getRelative(axis.left))
            pending.addLast(block.getRelative(axis.right))
        }
        if (portalBlocks.isEmpty()) return Optional.empty()
        return Optional.of(CustomPortalBounds(axis, portalBlocks))
    }

    private fun register(world: World, bounds: CustomPortalBounds) {
        val portals = portalsByWorld.computeIfAbsent(world.uid) { HashMap() }
        val replaced = HashSet<CustomPortalBounds>()
        for (block in bounds.blocks()) {
            val previous = portals[blockKey(block.x(), block.y(), block.z())]
            if (previous != null && previous !== bounds) replaced.add(previous)
        }
        replaced.forEach { remove(portals, it) }
        bounds.blocks().forEach { portals[blockKey(it.x(), it.y(), it.z())] = bounds }
    }

    companion object {
        private const val MAX_PORTAL_BLOCKS = 256 * 256
        private val FRAME_NEIGHBOURS = listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        @JvmStatic fun axisOf(block: Block): PortalAxis? {
            if (block.type != Material.NETHER_PORTAL) return null
            val orientable = block.blockData as? Orientable ?: return null
            return PortalAxis.from(orientable.axis)
        }
        private fun remove(portals: MutableMap<Long, CustomPortalBounds>, bounds: CustomPortalBounds) {
            bounds.blocks().forEach { portals.remove(blockKey(it.x(), it.y(), it.z()), bounds) }
        }
        private fun positionOf(block: Block): CustomPortalBounds.BlockPosition = CustomPortalBounds.BlockPosition(block.x, block.y, block.z)
        private fun blockKey(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 67108863L) shl 38) or ((z.toLong() and 67108863L) shl 12) or (y.toLong() and 4095L)
    }
}
