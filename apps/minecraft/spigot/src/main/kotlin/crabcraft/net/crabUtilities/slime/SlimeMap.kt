package crabcraft.net.crabUtilities.slime

import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/** A six-row slime-chunk map oriented in the direction the player is facing. */
class SlimeMap private constructor(player: Player) : InventoryHolder {
    private val inventory = Bukkit.createInventory(this, SIZE, CrabMessages.accent("Slime Chunks"))

    init {
        val location = player.location
        val world = player.world
        val centreChunkX = location.blockX shr 4
        val centreChunkZ = location.blockZ shr 4
        for (slot in 0 until SIZE) {
            val offset = chunkOffsetAt(location.yaw, slot)
            val chunkX = centreChunkX + offset.x()
            val chunkZ = centreChunkZ + offset.z()
            val currentChunk = offset.x() == 0 && offset.z() == 0
            val slimeChunk = world.getChunkAt(chunkX, chunkZ, false).isSlimeChunk
            inventory.setItem(slot, createMapItem(chunkX, chunkZ, currentChunk, slimeChunk))
        }
    }

    override fun getInventory(): Inventory = inventory

    data class ChunkOffset(private val x: Int, private val z: Int) {
        fun x() = x

        fun z() = z
    }

    companion object {
        private const val WIDTH = 9
        private const val SIZE = 54
        private const val CENTER_COLUMN = 4
        private const val CENTER_ROW = 3
        private val X_ROTATION = intArrayOf(1, 0, -1, 0)
        private val Z_ROTATION = intArrayOf(0, 1, 0, -1)

        @JvmStatic
        fun open(player: Player) {
            player.openInventory(SlimeMap(player).inventory)
        }

        private fun createMapItem(chunkX: Int, chunkZ: Int, currentChunk: Boolean, slimeChunk: Boolean): ItemStack {
            val material =
                when {
                    currentChunk -> Material.BLUE_STAINED_GLASS_PANE
                    slimeChunk -> Material.LIME_STAINED_GLASS_PANE
                    else -> Material.RED_STAINED_GLASS_PANE
                }
            val item = ItemStack(material)
            val meta = item.itemMeta
            val position = "(${chunkX shl 4}, ${chunkZ shl 4})"
            meta.displayName(
                plain(
                    if (currentChunk) CrabMessages.highlight("You are here ").append(CrabMessages.text(position))
                    else CrabMessages.text(position)
                )
            )
            meta.lore(
                listOf(
                    plain(
                        if (slimeChunk) CrabMessages.success("Slime chunk") else CrabMessages.error("Not a slime chunk")
                    ),
                    plain(CrabMessages.muted("Chunk $chunkX, $chunkZ")),
                )
            )
            item.itemMeta = meta
            return item
        }

        private fun plain(component: Component) = component.decoration(TextDecoration.ITALIC, false)

        @JvmStatic
        fun chunkOffsetAt(yaw: Float, slot: Int): ChunkOffset {
            require(slot in 0 until SIZE) { "slot must be between 0 and ${SIZE - 1}" }
            val screenX = slot % WIDTH - CENTER_COLUMN
            val screenZ = slot / WIDTH - CENTER_ROW
            val direction = Math.floorMod(Math.round(yaw / 90.0f) + 2, X_ROTATION.size)
            val xRotation = X_ROTATION[direction]
            val zRotation = Z_ROTATION[direction]
            return ChunkOffset(screenX * xRotation - screenZ * zRotation, screenX * zRotation + screenZ * xRotation)
        }
    }
}
