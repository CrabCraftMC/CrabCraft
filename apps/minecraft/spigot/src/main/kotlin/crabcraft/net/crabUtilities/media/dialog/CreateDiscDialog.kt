package crabcraft.net.crabUtilities.media.dialog

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.item.MediaItemCodec
import crabcraft.net.crabUtilities.media.item.PlayableItemWriter

/** Creation and editing dialogs for a held disc. */
@Suppress("UnstableApiUsage")
class CreateDiscDialog private constructor() {
  companion object {
    @JvmStatic fun open(player: Player) {
      val plugin = MediaFeature.get()
      val lang = plugin.getMessages()
      if (!player.hasPermission("crabutilities.media.create")) {
        MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"))
        return
      }
      if (!MediaItemCodec.isMusicDiscHeld(player)) {
        MediaFeature.sendMessage(player, lang.prefixedComponent("command.create.messages.error.not-holding-disc"))
        return
      }
      show(player, "command.create.dialog.title", "", "", 100, plugin.getMediaConfig().getDiscRangeDefault())
    }

    @JvmStatic fun openForEdit(player: Player) {
      val plugin = MediaFeature.get()
      val lang = plugin.getMessages()
      if (!player.hasPermission("crabutilities.media.create")) {
        MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"))
        return
      }
      val held = player.inventory.itemInMainHand
      if (!MediaItemCodec.isDisc(held)) {
        MediaFeature.sendMessage(player, lang.prefixedComponent("command.edit.messages.error.not-media-disc"))
        return
      }
      val disc = MediaItemCodec.readDisc(held)
      val volPercent = Math.round(disc.volume() * 100)
      val distance = if (disc.range() > 0) disc.range() else plugin.getMediaConfig().getDiscRangeDefault()
      show(player, "command.edit.dialog.title", disc.source(), disc.storedName(), volPercent, distance)
    }

    private fun show(player: Player, titleKey: String, initUrl: String, initName: String,
                     initVolumePercent: Int, initDistance: Int) {
      val plugin = MediaFeature.get()
      val lang = plugin.getMessages()
      val rangeMin = plugin.getMediaConfig().getDiscRangeMin()
      val rangeMax = plugin.getMediaConfig().getDiscRangeMax()
      val clampedDistance = Math.max(rangeMin, Math.min(rangeMax, initDistance))
      val dialog = Dialog.create { b -> b.empty()
        .base(DialogBase.builder(lang.component(titleKey))
          .body(listOf(DialogBody.plainMessage(lang.component("command.create.dialog.body"))))
          .inputs(listOf(
            DialogInput.text("url", lang.component("command.create.dialog.input.url"))
              .initial(initUrl).maxLength(512).width(300).build(),
            DialogInput.text("name", lang.component("command.create.dialog.input.name"))
              .initial(initName).maxLength(64).width(300).build(),
            DialogInput.numberRange("volume", lang.component("command.create.dialog.input.volume"), 0f, 200f)
              .step(5f).initial(initVolumePercent.toFloat()).labelFormat("%s: %s%%").width(300).build(),
              DialogInput.numberRange("distance", lang.component("command.create.dialog.input.distance"),
                  rangeMin.toFloat(), rangeMax.toFloat())
                .step(1f).initial(clampedDistance.toFloat()).labelFormat("%s: %s blocks").width(300).build()
          ))
          .canCloseWithEscape(true).build())
        .type(DialogType.confirmation(
          ActionButton.create(lang.component("command.create.dialog.button.cancel"), null, 100, null),
          ActionButton.create(lang.component("command.create.dialog.button.create"), null, 100,
            DialogAction.customClick(::onSubmit, ClickCallback.Options.builder().uses(1).build()))
        )) }
      player.showDialog(dialog)
    }

    private fun onSubmit(view: DialogResponseView, audience: Audience) {
      val player = audience as? Player ?: return
      val url = view.getText("url")
      val name = view.getText("name")
      val volPercent = view.getFloat("volume")
      val volume = if (volPercent != null) volPercent / 100f else 1f
      val distF = view.getFloat("distance")
      val distance = if (distF != null) Math.round(distF) else 0
      Bukkit.getScheduler().runTask(MediaFeature.get().getJavaPlugin(), Runnable {
        PlayableItemWriter.writeDisc(player, url, name, volume, distance)
      })
    }
  }
}
