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

/** Creation and editing dialogs for a held horn. */
@Suppress("UnstableApiUsage")
class CreateHornDialog private constructor() {
  companion object {
    @JvmStatic fun open(player: Player) {
      val plugin = MediaFeature.get()
      val lang = plugin.getMessages()
      if (!player.hasPermission("crabutilities.media.create")) {
        MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"))
        return
      }
      if (!MediaItemCodec.isGoatHornHeld(player)) {
        MediaFeature.sendMessage(player, lang.prefixedComponent("command.horn.create.messages.error.not-holding-horn"))
        return
      }
      show(player, "command.horn.create.dialog.title", "", "", 100)
    }

    @JvmStatic fun openForEdit(player: Player) {
      val plugin = MediaFeature.get()
      val lang = plugin.getMessages()
      if (!player.hasPermission("crabutilities.media.create")) {
        MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"))
        return
      }
      val held = player.inventory.itemInMainHand
      if (!MediaItemCodec.isHorn(held)) {
        MediaFeature.sendMessage(player, lang.prefixedComponent("command.horn.edit.messages.error.not-media-horn"))
        return
      }
      val horn = MediaItemCodec.readHorn(held)
      val volPercent = Math.round(horn.volume() * 100)
      show(player, "command.horn.edit.dialog.title", horn.source(), horn.storedName(), volPercent)
    }

    private fun show(player: Player, titleKey: String, initUrl: String, initName: String,
                     initVolumePercent: Int) {
      val plugin = MediaFeature.get()
      val lang = plugin.getMessages()
      val dialog = Dialog.create { b -> b.empty()
        .base(DialogBase.builder(lang.component(titleKey))
          .body(listOf(DialogBody.plainMessage(lang.component("command.horn.create.dialog.body"))))
          .inputs(listOf(
            DialogInput.text("url", lang.component("command.horn.create.dialog.input.url"))
              .initial(initUrl).maxLength(512).width(300).build(),
            DialogInput.text("name", lang.component("command.horn.create.dialog.input.name"))
              .initial(initName).maxLength(64).width(300).build(),
            DialogInput.numberRange("volume", lang.component("command.horn.create.dialog.input.volume"), 0f, 200f)
              .step(5f).initial(initVolumePercent.toFloat()).labelFormat("%s: %s%%").width(300).build()
          ))
          .canCloseWithEscape(true).build())
        .type(DialogType.confirmation(
          ActionButton.create(lang.component("command.horn.create.dialog.button.cancel"), null, 100, null),
          ActionButton.create(lang.component("command.horn.create.dialog.button.create"), null, 100,
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
      Bukkit.getScheduler().runTask(MediaFeature.get().getJavaPlugin(), Runnable {
        PlayableItemWriter.writeHorn(player, url, name, volume)
      })
    }
  }
}
