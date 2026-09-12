package crabcraft.net.crabUtilities.media.item

import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.audio.AudioEngine
import crabcraft.net.crabUtilities.media.source.MediaSourceKind
import crabcraft.net.crabUtilities.media.util.RemoteMediaSecurity
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.keys.InstrumentKeys
import io.papermc.paper.registry.keys.SoundEventKeys
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.MusicInstrument
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** Applies validated playback metadata to the item in a player's main hand. */
class PlayableItemWriter private constructor() {
  companion object {
    @JvmStatic @Suppress("UnstableApiUsage")
    fun writeDisc(player: Player, source: String?, name: String?, volume: Float, range: Int) {
      val feature = MediaFeature.get()
      val sourceKind = authorisedSource(player, source) ?: return
      if (!RemoteMediaSecurity.isValidDiscSettings(volume, range,
          feature.getMediaConfig().getDiscRangeMin(), feature.getMediaConfig().getDiscRangeMax())) {
        reject(player, "error.command.invalid-settings")
        return
      }
      if (!MediaItemCodec.isMusicDiscHeld(player)) {
        reject(player, "command.create.messages.error.not-holding-disc")
        return
      }
      if (!hasName(player, name, "error.command.disc-name-empty")) return
      val held = player.inventory.itemInMainHand
      val meta = MediaItemCodec.requireMeta(held)
      decorate(meta, name!!)
      MediaItemCodec.writeDisc(meta, source!!, name, volume, range)
      held.itemMeta = meta
      val model = sourceKind.itemModel(feature.getMediaConfig())
      if (model != 0) {
        held.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
          CustomModelData.customModelData().addFloat(model.toFloat()).build())
      }
      describeDisc(player, name, source, volume, range)
    }

    @JvmStatic @Suppress("UnstableApiUsage")
    fun writeHorn(player: Player, source: String?, name: String?, volume: Float) {
      val feature = MediaFeature.get()
      if (authorisedSource(player, source) == null) return
      if (!RemoteMediaSecurity.isValidVolume(volume)) {
        reject(player, "error.command.invalid-settings")
        return
      }
      if (!MediaItemCodec.isGoatHornHeld(player)) {
        reject(player, "command.horn.create.messages.error.not-holding-horn")
        return
      }
      if (!hasName(player, name, "error.command.horn-name-empty")) return
      val held = player.inventory.itemInMainHand
      val originalInstrument = originalInstrument(held)
      val meta = MediaItemCodec.requireMeta(held)
      decorate(meta, name!!)
      MediaItemCodec.writeHorn(meta, source!!, name, volume, originalInstrument)
      held.itemMeta = meta
      val title = shortened(name)
      val duration = Math.max(1, feature.getMediaConfig().getHornMaxLengthSeconds()).toFloat()
      held.setData(DataComponentTypes.INSTRUMENT, MusicInstrument.create { factory -> factory.empty()
        .soundEvent(SoundEventKeys.INTENTIONALLY_EMPTY).duration(duration).range(16f).description(Component.text(title)) })
      describeHorn(player, name, source, volume)
      prewarmHorn(player, source, volume)
    }

    @JvmStatic @Suppress("UnstableApiUsage")
    fun clearDisc(player: Player) {
      if (!canClear(player)) return
      val held = player.inventory.itemInMainHand
      if (!MediaItemCodec.isMusicDiscHeld(player) || !MediaItemCodec.isDisc(held)) {
        reject(player, "command.clear.messages.error.not-media-disc")
        return
      }
      val meta = MediaItemCodec.requireMeta(held)
      MediaItemCodec.clearDisc(meta)
      restoreAppearance(meta)
      held.itemMeta = meta
      held.resetData(DataComponentTypes.CUSTOM_MODEL_DATA)
      player.sendMessage(MediaFeature.get().getMessages().component("command.clear.messages.cleared"))
    }

    @JvmStatic @Suppress("UnstableApiUsage")
    fun clearHorn(player: Player) {
      if (!canClear(player)) return
      val held = player.inventory.itemInMainHand
      if (!MediaItemCodec.isHorn(held)) {
        reject(player, "command.horn.clear.messages.error.not-media-horn")
        return
      }
      val originalInstrument = MediaItemCodec.readOriginalHornInstrument(held)
      val meta = MediaItemCodec.requireMeta(held)
      MediaItemCodec.clearHorn(meta)
      restoreAppearance(meta)
      held.itemMeta = meta
      held.setData(DataComponentTypes.INSTRUMENT, resolveInstrument(originalInstrument))
      player.sendMessage(MediaFeature.get().getMessages().component("command.horn.clear.messages.cleared"))
    }

    private fun authorisedSource(player: Player, source: String?): MediaSourceKind? {
      if (source == null || source.isBlank()) {
        reject(player, "error.command.url-empty")
        return null
      }
      val feature = MediaFeature.get()
      val kind = MediaSourceKind.classify(source, feature.getMediaConfig())
      if (!RemoteMediaSecurity.canCreate(player::hasPermission, kind)) {
        reject(player, "error.command.no-permission")
        return null
      }
      return kind
    }

    private fun hasName(player: Player, name: String?, errorKey: String): Boolean {
      if (name != null && !name.isBlank()) return true
      reject(player, errorKey)
      return false
    }

    private fun decorate(meta: ItemMeta, name: String) {
      meta.displayName(Component.text(shortened(name), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
      meta.addItemFlags(*ItemFlag.values())
    }

    private fun restoreAppearance(meta: ItemMeta) {
      meta.displayName(null)
      meta.removeItemFlags(*ItemFlag.values())
    }

    @Suppress("UnstableApiUsage")
    private fun originalInstrument(horn: ItemStack): String? {
      if (MediaItemCodec.isHorn(horn)) return null
      val instrument = horn.getData(DataComponentTypes.INSTRUMENT) ?: return null
      return RegistryAccess.registryAccess().getRegistry(RegistryKey.INSTRUMENT).getKeyOrThrow(instrument).toString()
    }

    private fun resolveInstrument(key: String?): MusicInstrument {
      val instruments = RegistryAccess.registryAccess().getRegistry(RegistryKey.INSTRUMENT)
      val namespacedKey = if (key != null) NamespacedKey.fromString(key) else null
      val instrument = if (namespacedKey != null) instruments.get(namespacedKey) else null
      return instrument ?: instruments.getOrThrow(InstrumentKeys.PONDER_GOAT_HORN)
    }

    private fun canClear(player: Player): Boolean {
      if (player.hasPermission("crabutilities.media.create")) return true
      reject(player, "error.command.no-permission")
      return false
    }

    private fun shortened(name: String): String = if (name.length <= 32) name else name.substring(0, 32) + "..."

    private fun describeDisc(player: Player, name: String, source: String, volume: Float, range: Int) {
      val messages = MediaFeature.get().getMessages()
      player.sendMessage(messages.component("command.create.messages.created"))
      player.sendMessage(messages.component("command.create.messages.name", name))
      player.sendMessage(messages.component("command.create.messages.source", source))
      val percentage = Math.round(volume * 100)
      if (percentage != 100) player.sendMessage(messages.component("command.create.messages.volume", percentage))
      if (range > 0) player.sendMessage(messages.component("command.create.messages.distance", range))
    }

    private fun describeHorn(player: Player, name: String, source: String, volume: Float) {
      val messages = MediaFeature.get().getMessages()
      player.sendMessage(messages.component("command.horn.create.messages.created"))
      player.sendMessage(messages.component("command.horn.create.messages.name", name))
      player.sendMessage(messages.component("command.horn.create.messages.source", source))
      val percentage = Math.round(volume * 100)
      if (percentage != 100) player.sendMessage(messages.component("command.horn.create.messages.volume", percentage))
    }

    private fun prewarmHorn(player: Player, source: String, itemVolume: Float) {
      val feature = MediaFeature.get()
      val limit = feature.getMediaConfig().getHornMaxLengthSeconds()
      val effectiveVolume = feature.getMediaConfig().getHornVolume() * itemVolume
      AudioEngine.getInstance().prewarmHornAsync(source, effectiveVolume) { track ->
        val duration = track?.durationSeconds()
        if (duration != null && duration > limit) {
          player.sendMessage(feature.getMessages().component("command.horn.create.messages.length-warning", limit))
        }
      }
    }

    private fun reject(player: Player, messageKey: String) {
      player.sendMessage(MediaFeature.get().getMessages().prefixedComponent(messageKey))
    }
  }
}
