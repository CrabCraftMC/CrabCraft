package crabcraft.net.crabUtilities.netherportals

import org.bukkit.Material
import org.bukkit.block.Block

/** Immutable snapshot of custom portal settings, refreshed after module reload. */
data class PortalSettings(
    private val frameMaterials: Set<Material>, private val minPortalSize: Int,
    private val maxPortalWidth: Int, private val maxPortalHeight: Int
) {
    fun frameMaterials(): Set<Material> = frameMaterials
    fun minPortalSize(): Int = minPortalSize
    fun maxPortalWidth(): Int = maxPortalWidth
    fun maxPortalHeight(): Int = maxPortalHeight
    fun isPortalFrame(block: Block?): Boolean = block != null && frameMaterials.contains(block.type)
}
