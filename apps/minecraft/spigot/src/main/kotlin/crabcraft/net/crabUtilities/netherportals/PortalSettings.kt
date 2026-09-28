package crabcraft.net.crabUtilities.netherportals

import org.bukkit.Material
import org.bukkit.block.Block

/** Immutable snapshot of custom portal settings, invalidated on tweaks reload. */
data class PortalSettings(
    private val frameMaterials: Set<Material>,
    private val minPortalSize: Int,
    private val maxPortalWidth: Int,
    private val maxPortalHeight: Int,
) {
    fun frameMaterials() = frameMaterials

    fun minPortalSize() = minPortalSize

    fun maxPortalWidth() = maxPortalWidth

    fun maxPortalHeight() = maxPortalHeight

    fun isPortalFrame(block: Block?) = block != null && frameMaterials.contains(block.type)
}
