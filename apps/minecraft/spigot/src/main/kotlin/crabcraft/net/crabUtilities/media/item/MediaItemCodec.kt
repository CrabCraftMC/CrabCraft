package crabcraft.net.crabUtilities.media.item

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
import java.util.UUID

/**
 * Encodes Crab Utilities playback data on items.
 *
 * The namespace and key names are a save-format contract. They deliberately
 * retain their historical values so existing inventories and world containers
 * continue to work after the implementation change.
 */
class MediaItemCodec private constructor() {
  data class DiscData(private val title: Component, private val source: String, private val volume: Float,
                      private val range: Int, private val storedName: String) {
    fun title(): Component = title
    fun source(): String = source
    fun volume(): Float = volume
    fun range(): Int = range
    fun storedName(): String = storedName
  }

  data class HornData(private val source: String, private val volume: Float, private val storedName: String) {
    fun source(): String = source
    fun volume(): Float = volume
    fun storedName(): String = storedName
  }

  companion object {
    private val DISC_SOURCE = key("remote")
    private val DISC_VOLUME = key("volume")
    private val DISC_RANGE = key("distance")
    private val DISC_NAME = key("name")
    private val HORN_SOURCE = key("horn_remote")
    private val HORN_VOLUME = key("horn_volume")
    private val HORN_NAME = key("horn_name")
    private val HORN_ORIGINAL_INSTRUMENT = key("horn_original_instrument")

    @JvmStatic fun isMusicDiscHeld(player: Player): Boolean =
      player.inventory.itemInMainHand.type.name.startsWith("MUSIC_DISC_")

    @JvmStatic fun jukeboxHasRecord(block: Block): Boolean {
      val jukebox = block.state as? Jukebox ?: return false
      return jukebox.record.type != Material.AIR
    }

    @JvmStatic fun isDisc(item: ItemStack?): Boolean {
      if (item == null || !item.hasItemMeta()) return false
      return requireMeta(item).persistentDataContainer.has(DISC_SOURCE, PersistentDataType.STRING)
    }

    @JvmStatic fun readDisc(item: ItemStack?): DiscData {
      if (!isDisc(item)) throw IllegalArgumentException("Item has no media-disc source")
      val meta = requireMeta(item!!)
      val data = meta.persistentDataContainer
      val source = data.get(DISC_SOURCE, PersistentDataType.STRING)!!
      val volume = data.get(DISC_VOLUME, PersistentDataType.FLOAT)
      val range = data.get(DISC_RANGE, PersistentDataType.INTEGER)
      val storedName = data.get(DISC_NAME, PersistentDataType.STRING)
      return DiscData(displayTitle(meta), source, volume ?: 1f, range ?: 0, storedName ?: "")
    }

    @JvmStatic fun writeDisc(meta: ItemMeta, source: String, name: String, volume: Float, range: Int) {
      val data = meta.persistentDataContainer
      clearDiscData(data)
      data.set(DISC_SOURCE, PersistentDataType.STRING, source)
      data.set(DISC_VOLUME, PersistentDataType.FLOAT, volume)
      data.set(DISC_RANGE, PersistentDataType.INTEGER, range)
      data.set(DISC_NAME, PersistentDataType.STRING, name)
    }

    @JvmStatic fun isGoatHornHeld(player: Player): Boolean = player.inventory.itemInMainHand.type == Material.GOAT_HORN

    @JvmStatic fun isHorn(item: ItemStack?): Boolean = item != null && item.type == Material.GOAT_HORN
      && item.hasItemMeta() && requireMeta(item).persistentDataContainer.has(HORN_SOURCE, PersistentDataType.STRING)

    @JvmStatic fun readHorn(item: ItemStack?): HornData {
      if (!isHorn(item)) throw IllegalArgumentException("Item has no media-horn source")
      val data = requireMeta(item!!).persistentDataContainer
      val source = data.get(HORN_SOURCE, PersistentDataType.STRING)!!
      val volume = data.get(HORN_VOLUME, PersistentDataType.FLOAT)
      val storedName = data.get(HORN_NAME, PersistentDataType.STRING)
      return HornData(source, volume ?: 1f, storedName ?: "")
    }

    @JvmStatic fun writeHorn(meta: ItemMeta, source: String, name: String, volume: Float, originalInstrument: String?) {
      val data = meta.persistentDataContainer
      clearHornMediaData(data)
      if (originalInstrument != null) data.set(HORN_ORIGINAL_INSTRUMENT, PersistentDataType.STRING, originalInstrument)
      data.set(HORN_SOURCE, PersistentDataType.STRING, source)
      data.set(HORN_VOLUME, PersistentDataType.FLOAT, volume)
      data.set(HORN_NAME, PersistentDataType.STRING, name)
    }

    @JvmStatic fun readOriginalHornInstrument(item: ItemStack?): String? {
      if (!isHorn(item)) throw IllegalArgumentException("Item has no media-horn source")
      return requireMeta(item!!).persistentDataContainer.get(HORN_ORIGINAL_INSTRUMENT, PersistentDataType.STRING)
    }

    @JvmStatic fun clearDisc(meta: ItemMeta) { clearDiscData(meta.persistentDataContainer) }
    @JvmStatic fun clearHorn(meta: ItemMeta) { clearHornData(meta.persistentDataContainer) }
    @JvmStatic fun requireMeta(item: ItemStack): ItemMeta = item.itemMeta ?: throw IllegalArgumentException("Item has no metadata")

    @JvmStatic fun playbackId(block: Block): UUID {
      val worldAndHeight = block.world.uid.leastSignificantBits xor block.y.toLong()
      val horizontal = (block.x.toLong() shl 32) or (block.z.toLong() and 0xFFFFFFFFL)
      return UUID(worldAndHeight, horizontal)
    }

    @JvmStatic fun compatibilityKeys(): Map<String, NamespacedKey> {
      val keys = linkedMapOf(
        "remote" to DISC_SOURCE, "volume" to DISC_VOLUME, "distance" to DISC_RANGE, "name" to DISC_NAME,
        "horn_remote" to HORN_SOURCE, "horn_volume" to HORN_VOLUME, "horn_name" to HORN_NAME,
        "horn_original_instrument" to HORN_ORIGINAL_INSTRUMENT
      )
      return java.util.Map.copyOf(keys)
    }

    @JvmStatic fun clearDiscData(data: PersistentDataContainer) {
      data.remove(DISC_SOURCE)
      data.remove(DISC_VOLUME)
      data.remove(DISC_RANGE)
      data.remove(DISC_NAME)
    }

    @JvmStatic fun clearHornData(data: PersistentDataContainer) {
      clearHornMediaData(data)
      data.remove(HORN_ORIGINAL_INSTRUMENT)
    }

    private fun clearHornMediaData(data: PersistentDataContainer) {
      data.remove(HORN_SOURCE)
      data.remove(HORN_VOLUME)
      data.remove(HORN_NAME)
    }

    private fun displayTitle(meta: ItemMeta): Component {
      val lore = meta.lore()
      return if (lore == null || lore.isEmpty()) Component.text("Unknown", NamedTextColor.GRAY) else lore.first()
    }

    private fun key(value: String): NamespacedKey = NamespacedKey.fromString("customdiscs:" + value)!!
  }
}
