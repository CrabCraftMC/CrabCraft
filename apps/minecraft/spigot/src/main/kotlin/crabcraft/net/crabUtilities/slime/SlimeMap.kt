package crabcraft.net.crabUtilities.slime

import crabcraft.net.crabUtilities.CrabMessages
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** A six-row slime-chunk map oriented in the direction the player is facing. */
class SlimeMap private constructor(player: Player) : InventoryHolder {
    private val inventory = Bukkit.createInventory(this, SIZE, CrabMessages.accent("Slime Chunks"))
    init { populate(player) }
    private fun populate(player: Player) {
        val location = player.getLocation()
        val world = player.getWorld()
        val centerChunkX = location.getBlockX() shr 4
        val centerChunkZ = location.getBlockZ() shr 4
        for (slot in 0 until SIZE) {
            val offset = chunkOffsetAt(location.getYaw(), slot)
            val chunkX = centerChunkX + offset.x()
            val chunkZ = centerChunkZ + offset.z()
            val currentChunk = offset.x() == 0 && offset.z() == 0
            val slimeChunk = world.getChunkAt(chunkX, chunkZ, false).isSlimeChunk
            inventory.setItem(slot, createMapItem(chunkX, chunkZ, currentChunk, slimeChunk))
        }
    }
    override fun getInventory(): Inventory = inventory
    data class ChunkOffset(private val x: Int, private val z: Int) {
        fun x(): Int = x
        fun z(): Int = z
    }
    companion object {
        private const val WIDTH = 9
        private const val SIZE = 54
        private const val CENTER_COLUMN = 4
        private const val CENTER_ROW = 3
        private val X_ROTATION = intArrayOf(1, 0, -1, 0)
        private val Z_ROTATION = intArrayOf(0, 1, 0, -1)
        @JvmStatic fun open(player: Player) { player.openInventory(SlimeMap(player).inventory) }
        private fun createMapItem(chunkX: Int, chunkZ: Int, currentChunk: Boolean, slimeChunk: Boolean): ItemStack {
            val material = if (currentChunk) Material.BLUE_STAINED_GLASS_PANE else if (slimeChunk) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE
            val item = ItemStack(material)
            val meta = item.getItemMeta()!!
            val position = "(${chunkX shl 4}, ${chunkZ shl 4})"
            meta.displayName(plain(if (currentChunk) CrabMessages.highlight("You are here ").append(CrabMessages.text(position)) else CrabMessages.text(position)))
            meta.lore(listOf(plain(if (slimeChunk) CrabMessages.success("Slime chunk") else CrabMessages.error("Not a slime chunk")),
                plain(CrabMessages.muted("Chunk $chunkX, $chunkZ"))))
            item.setItemMeta(meta)
            return item
        }
        private fun plain(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)
        @JvmStatic
        fun chunkOffsetAt(yaw: Float, slot: Int): ChunkOffset {
            if (slot < 0 || slot >= SIZE) throw IllegalArgumentException("slot must be between 0 and " + (SIZE - 1))
            val screenX = slot % WIDTH - CENTER_COLUMN
            val screenZ = slot / WIDTH - CENTER_ROW
            val direction = Math.floorMod(Math.round(yaw / 90.0F) + 2, X_ROTATION.size)
            val xRotation = X_ROTATION[direction]
            val zRotation = Z_ROTATION[direction]
            return ChunkOffset(screenX * xRotation - screenZ * zRotation, screenX * zRotation + screenZ * xRotation)
        }
    }
}
