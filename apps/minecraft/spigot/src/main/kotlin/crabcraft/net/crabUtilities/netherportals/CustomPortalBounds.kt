package crabcraft.net.crabUtilities.netherportals

import java.util.Optional

/** Complete, uncapped bounds and block membership for one custom portal. */
class CustomPortalBounds(private val axis: PortalAxis, blocks: Collection<BlockPosition>) {
    private val blocks: Set<BlockPosition>
    private val bottomBlocks: List<BlockPosition>
    private val minY: Int
    private val maxY: Int
    private val minFlat: Int
    private val maxFlat: Int

    init {
        if (blocks.isEmpty()) throw IllegalArgumentException("A portal must contain at least one block")
        this.blocks = java.util.Set.copyOf(blocks)
        minY = this.blocks.minOf { it.y() }
        maxY = this.blocks.maxOf { it.y() }
        minFlat = this.blocks.minOf(::flat)
        maxFlat = this.blocks.maxOf(::flat)
        bottomBlocks = this.blocks.filter { it.y() == minY }
    }

    fun axis(): PortalAxis = axis
    fun blocks(): Set<BlockPosition> = blocks
    fun contains(block: BlockPosition): Boolean = blocks.contains(block)
    fun overlaps(other: CustomPortalBounds): Boolean {
        val smaller = if (blocks.size <= other.blocks.size) blocks else other.blocks
        val larger = if (smaller === blocks) other.blocks else blocks
        return smaller.any(larger::contains)
    }
    fun minY(): Int = minY
    fun width(): Int = maxFlat - minFlat + 1
    fun height(): Int = maxY - minY + 1
    fun exceedsVanillaInteriorLimit(): Boolean = width() > VANILLA_MAX_INTERIOR_SIZE || height() > VANILLA_MAX_INTERIOR_SIZE

    fun findSafeDestination(targetX: Double, targetZ: Double, safety: Safety): Optional<Destination> {
        val outside = ArrayList<Candidate>(bottomBlocks.size * 2)
        val inside = ArrayList<Candidate>(bottomBlocks.size)
        for (portalBlock in bottomBlocks) {
            if (axis == PortalAxis.X) {
                outside.add(Candidate(portalBlock, Destination(portalBlock.x() + 0.5, portalBlock.y().toDouble(), portalBlock.z() - 0.5)))
                outside.add(Candidate(portalBlock, Destination(portalBlock.x() + 0.5, portalBlock.y().toDouble(), portalBlock.z() + 1.5)))
            } else {
                outside.add(Candidate(portalBlock, Destination(portalBlock.x() - 0.5, portalBlock.y().toDouble(), portalBlock.z() + 0.5)))
                outside.add(Candidate(portalBlock, Destination(portalBlock.x() + 1.5, portalBlock.y().toDouble(), portalBlock.z() + 0.5)))
            }
            inside.add(Candidate(portalBlock, Destination(portalBlock.x() + 0.5, portalBlock.y().toDouble(), portalBlock.z() + 0.5)))
        }
        val closestToVanillaTarget = compareBy<Candidate> { it.destination.horizontalDistanceSquared(targetX, targetZ) }
            .thenBy { it.destination.x() }.thenBy { it.destination.z() }
        val outsideDestination = outside.filter { isSafe(it, safety) }.minWithOrNull(closestToVanillaTarget)?.destination
        if (outsideDestination != null) return Optional.of(outsideDestination)
        // The lower portal block itself is the reliable fallback: its lower
        // frame is directly beneath it even when no exterior platform exists.
        return Optional.ofNullable(inside.filter { isSafe(it, safety) }.minWithOrNull(closestToVanillaTarget)?.destination)
    }

    private fun flat(block: BlockPosition): Int = if (axis == PortalAxis.X) block.x() else block.z()

    interface Safety {
        fun isPortal(block: BlockPosition): Boolean
        fun canOccupy(destination: Destination): Boolean
        fun hasSupport(feet: BlockPosition): Boolean
    }

    data class BlockPosition(private val x: Int, private val y: Int, private val z: Int) {
        fun x(): Int = x
        fun y(): Int = y
        fun z(): Int = z
    }

    data class Destination(private val x: Double, private val y: Double, private val z: Double) {
        fun x(): Double = x
        fun y(): Double = y
        fun z(): Double = z
        fun feetBlock(): BlockPosition = BlockPosition(floor(x), floor(y), floor(z))
        fun horizontalDistanceSquared(targetX: Double, targetZ: Double): Double {
            val deltaX = x - targetX
            val deltaZ = z - targetZ
            return deltaX * deltaX + deltaZ * deltaZ
        }
        companion object {
            private fun floor(value: Double): Int {
                val integer = value.toInt()
                return if (value < integer) integer - 1 else integer
            }
        }
    }

    private data class Candidate(val portalBlock: BlockPosition, val destination: Destination)
    companion object {
        const val VANILLA_MAX_INTERIOR_SIZE = 21
        private fun isSafe(candidate: Candidate, safety: Safety): Boolean {
            val feet = candidate.destination.feetBlock()
            return safety.isPortal(candidate.portalBlock) && safety.canOccupy(candidate.destination) && safety.hasSupport(feet)
        }
    }
}
