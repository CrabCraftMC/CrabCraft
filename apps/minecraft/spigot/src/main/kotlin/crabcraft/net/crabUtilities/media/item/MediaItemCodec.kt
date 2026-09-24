package crabcraft.net.crabUtilities.media.item

import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.Jukebox
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Encodes playback data using the historical namespace and keys, a save-format contract that keeps existing inventories
 * and world containers compatible.
 */
object MediaItemCodec {
    private val DISC_SOURCE = key("remote")
    private val DISC_VOLUME = key("volume")
    private val DISC_RANGE = key("distance")
    private val DISC_NAME = key("name")
    private val HORN_SOURCE = key("horn_remote")
    private val HORN_VOLUME = key("horn_volume")
    private val HORN_NAME = key("horn_name")
    private val HORN_ORIGINAL_INSTRUMENT = key("horn_original_instrument")

    data class DiscData(
        val title: Component,
        val source: String,
        val volume: Float,
        val range: Int,
        val storedName: String,
    ) {
        fun title() = title

        fun source() = source

        fun volume() = volume

        fun range() = range

        fun storedName() = storedName
    }

    data class HornData(val source: String, val volume: Float, val storedName: String) {
        fun source() = source

        fun volume() = volume

        fun storedName() = storedName
    }

    @JvmStatic fun isMusicDiscHeld(player: Player) = player.inventory.itemInMainHand.type.name.startsWith("MUSIC_DISC_")

    @JvmStatic
    fun jukeboxHasRecord(block: Block): Boolean =
        (block.state as? Jukebox)?.record?.type?.let { it != Material.AIR } ?: false

    @JvmStatic
    fun isDisc(item: ItemStack?) =
        item != null &&
            item.hasItemMeta() &&
            requireMeta(item).persistentDataContainer.has(DISC_SOURCE, PersistentDataType.STRING)

    @JvmStatic
    fun readDisc(item: ItemStack): DiscData {
        require(isDisc(item)) { "Item has no media-disc source" }
        val meta = requireMeta(item)
        val data = meta.persistentDataContainer
        return DiscData(
            displayTitle(meta),
            data.get(DISC_SOURCE, PersistentDataType.STRING)!!,
            data.get(DISC_VOLUME, PersistentDataType.FLOAT) ?: 1f,
            data.get(DISC_RANGE, PersistentDataType.INTEGER) ?: 0,
            data.get(DISC_NAME, PersistentDataType.STRING) ?: "",
        )
    }

    @JvmStatic
    fun writeDisc(meta: ItemMeta, source: String, name: String, volume: Float, range: Int) {
        val data = meta.persistentDataContainer
        clearDiscData(data)
        data.set(DISC_SOURCE, PersistentDataType.STRING, source)
        data.set(DISC_VOLUME, PersistentDataType.FLOAT, volume)
        data.set(DISC_RANGE, PersistentDataType.INTEGER, range)
        data.set(DISC_NAME, PersistentDataType.STRING, name)
    }

    @JvmStatic fun isGoatHornHeld(player: Player) = player.inventory.itemInMainHand.type == Material.GOAT_HORN

    @JvmStatic
    fun isHorn(item: ItemStack?) =
        item != null &&
            item.type == Material.GOAT_HORN &&
            item.hasItemMeta() &&
            requireMeta(item).persistentDataContainer.has(HORN_SOURCE, PersistentDataType.STRING)

    @JvmStatic
    fun readHorn(item: ItemStack): HornData {
        require(isHorn(item)) { "Item has no media-horn source" }
        val data = requireMeta(item).persistentDataContainer
        return HornData(
            data.get(HORN_SOURCE, PersistentDataType.STRING)!!,
            data.get(HORN_VOLUME, PersistentDataType.FLOAT) ?: 1f,
            data.get(HORN_NAME, PersistentDataType.STRING) ?: "",
        )
    }

    @JvmStatic
    fun writeHorn(meta: ItemMeta, source: String, name: String, volume: Float, originalInstrument: String?) {
        val data = meta.persistentDataContainer
        clearHornMediaData(data)
        if (originalInstrument != null)
            data.set(HORN_ORIGINAL_INSTRUMENT, PersistentDataType.STRING, originalInstrument)
        data.set(HORN_SOURCE, PersistentDataType.STRING, source)
        data.set(HORN_VOLUME, PersistentDataType.FLOAT, volume)
        data.set(HORN_NAME, PersistentDataType.STRING, name)
    }

    @JvmStatic
    fun readOriginalHornInstrument(item: ItemStack): String? {
        require(isHorn(item)) { "Item has no media-horn source" }
        return requireMeta(item).persistentDataContainer.get(HORN_ORIGINAL_INSTRUMENT, PersistentDataType.STRING)
    }

    @JvmStatic fun clearDisc(meta: ItemMeta) = clearDiscData(meta.persistentDataContainer)

    @JvmStatic fun clearHorn(meta: ItemMeta) = clearHornData(meta.persistentDataContainer)

    @JvmStatic
    fun requireMeta(item: ItemStack): ItemMeta = item.itemMeta ?: throw IllegalArgumentException("Item has no metadata")

    @JvmStatic
    fun playbackId(block: Block): UUID {
        val worldAndHeight = block.world.uid.leastSignificantBits xor block.y.toLong()
        val horizontal = (block.x.toLong() shl 32) or (block.z.toLong() and 0xFFFFFFFFL)
        return UUID(worldAndHeight, horizontal)
    }

    @JvmStatic
    fun compatibilityKeys(): Map<String, NamespacedKey> =
        java.util.Map.copyOf(
            linkedMapOf(
                "remote" to DISC_SOURCE,
                "volume" to DISC_VOLUME,
                "distance" to DISC_RANGE,
                "name" to DISC_NAME,
                "horn_remote" to HORN_SOURCE,
                "horn_volume" to HORN_VOLUME,
                "horn_name" to HORN_NAME,
                "horn_original_instrument" to HORN_ORIGINAL_INSTRUMENT,
            )
        )

    @JvmStatic
    fun clearDiscData(data: PersistentDataContainer) {
        data.remove(DISC_SOURCE)
        data.remove(DISC_VOLUME)
        data.remove(DISC_RANGE)
        data.remove(DISC_NAME)
    }

    @JvmStatic
    fun clearHornData(data: PersistentDataContainer) {
        clearHornMediaData(data)
        data.remove(HORN_ORIGINAL_INSTRUMENT)
    }

    private fun clearHornMediaData(data: PersistentDataContainer) {
        data.remove(HORN_SOURCE)
        data.remove(HORN_VOLUME)
        data.remove(HORN_NAME)
    }

    private fun displayTitle(meta: ItemMeta): Component =
        meta.lore()?.firstOrNull() ?: Component.text("Unknown", NamedTextColor.GRAY)

    private fun key(value: String) = NamespacedKey.fromString("customdiscs:$value")!!
}
