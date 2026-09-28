package crabcraft.net.crabUtilities.media.dialog

import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.item.MediaItemCodec
import crabcraft.net.crabUtilities.media.item.PlayableItemWriter
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

/** Creation and editing dialogs for held music discs and goat horns. */
@Suppress("UnstableApiUsage")
enum class CreateMediaDialog(private val prefix: String, private val itemName: String) {
    DISC("command", "disc"),
    HORN("command.horn", "horn");

    fun open(player: Player, edit: Boolean) {
        val plugin = MediaFeature.get()
        val lang = plugin.getMessages()
        if (!player.hasPermission("crabutilities.media.create")) {
            MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"))
            return
        }
        val held = player.inventory.itemInMainHand
        val valid =
            if (edit) {
                if (this == DISC) MediaItemCodec.isDisc(held) else MediaItemCodec.isHorn(held)
            } else {
                if (this == DISC) MediaItemCodec.isMusicDiscHeld(player) else MediaItemCodec.isGoatHornHeld(player)
            }
        val operation = if (edit) "edit" else "create"
        if (!valid) {
            val error = if (edit) "not-media-" else "not-holding-"
            MediaFeature.sendMessage(
                player,
                lang.prefixedComponent("$prefix.$operation.messages.error.$error$itemName"),
            )
            return
        }
        var url = ""
        var name = ""
        var volume = 100
        var distance = plugin.getMediaConfig().getDiscRangeDefault()
        if (edit && this == DISC) {
            val disc = MediaItemCodec.readDisc(held)
            url = disc.source()
            name = disc.storedName()
            volume = Math.round(disc.volume() * 100)
            if (disc.range() > 0) distance = disc.range()
        } else if (edit) {
            val horn = MediaItemCodec.readHorn(held)
            url = horn.source()
            name = horn.storedName()
            volume = Math.round(horn.volume() * 100)
        }
        val dialogPrefix = "$prefix.create.dialog."
        val inputs =
            arrayListOf<DialogInput>(
                DialogInput.text("url", lang.component(dialogPrefix + "input.url"))
                    .initial(url)
                    .maxLength(512)
                    .width(300)
                    .build(),
                DialogInput.text("name", lang.component(dialogPrefix + "input.name"))
                    .initial(name)
                    .maxLength(64)
                    .width(300)
                    .build(),
                DialogInput.numberRange("volume", lang.component(dialogPrefix + "input.volume"), 0f, 200f)
                    .step(5f)
                    .initial(volume.toFloat())
                    .labelFormat("%s: %s%%")
                    .width(300)
                    .build(),
            )
        if (this == DISC) {
            val rangeMin = plugin.getMediaConfig().getDiscRangeMin()
            val rangeMax = plugin.getMediaConfig().getDiscRangeMax()
            val clampedDistance = maxOf(rangeMin, minOf(rangeMax, distance))
            inputs.add(
                DialogInput.numberRange(
                        "distance",
                        lang.component(dialogPrefix + "input.distance"),
                        rangeMin.toFloat(),
                        rangeMax.toFloat(),
                    )
                    .step(1f)
                    .initial(clampedDistance.toFloat())
                    .labelFormat("%s: %s blocks")
                    .width(300)
                    .build()
            )
        }
        player.showDialog(
            Dialog.create { b ->
                b.empty()
                    .base(
                        DialogBase.builder(lang.component("$prefix.$operation.dialog.title"))
                            .body(listOf(DialogBody.plainMessage(lang.component(dialogPrefix + "body"))))
                            .inputs(inputs)
                            .canCloseWithEscape(true)
                            .build()
                    )
                    .type(
                        DialogType.confirmation(
                            ActionButton.create(lang.component(dialogPrefix + "button.cancel"), null, 100, null),
                            ActionButton.create(
                                lang.component(dialogPrefix + "button.create"),
                                null,
                                100,
                                DialogAction.customClick(::onSubmit, ClickCallback.Options.builder().uses(1).build()),
                            ),
                        )
                    )
            }
        )
    }

    private fun onSubmit(view: DialogResponseView, audience: Audience) {
        if (audience !is Player) return
        val url = view.getText("url")
        val name = view.getText("name")
        val volume = view.getFloat("volume")?.div(100f) ?: 1f
        val distance = (if (this == DISC) view.getFloat("distance") else null)?.let(Math::round) ?: 0
        Bukkit.getScheduler()
            .runTask(
                MediaFeature.get().getJavaPlugin(),
                Runnable {
                    if (this == DISC) PlayableItemWriter.writeDisc(audience, url, name, volume, distance)
                    else PlayableItemWriter.writeHorn(audience, url, name, volume)
                },
            )
    }
}
