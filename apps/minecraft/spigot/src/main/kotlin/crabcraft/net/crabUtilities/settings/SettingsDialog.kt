package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabMessages
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player
import java.util.UUID

/** The per-player settings screen, using Paper's server-side Dialog API. */
@Suppress("UnstableApiUsage")
open class SettingsDialog(private val settingsService: PlayerSettingsService) {
    open fun open(player: Player) {
        val uuid = player.getUniqueId()
        val current = settingsService.get(uuid)
        val options = ArrayList<SingleOptionDialogInput.OptionEntry>(ORDER.size)
        for (mode in ORDER) {
            options.add(SingleOptionDialogInput.OptionEntry.create(mode.id(), mini(mode.coloredLabel()), mode == current.getPhantomMode()))
        }
        val base = DialogBase.builder(mini(CrabMessages.HIGHLIGHT_TAG + "Settings"))
            .inputs(listOf(
                DialogInput.singleOption(PHANTOMS_KEY, mini(CrabMessages.HIGHLIGHT_TAG + "Phantoms"), options).build(),
                DialogInput.singleOption(MENTION_PINGS_KEY, mini(CrabMessages.HIGHLIGHT_TAG + "Chat pings"), toggleOptions(current.isMentionPings())).build(),
                DialogInput.singleOption(ACCEPT_MESSAGES_KEY, mini(CrabMessages.HIGHLIGHT_TAG + "Private messages"), toggleOptions(current.isAcceptMessages())).build(),
                DialogInput.singleOption(LOCATOR_BAR_KEY, mini(CrabMessages.HIGHLIGHT_TAG + "Locator bar"), toggleOptions(current.isLocatorBar())).build(),
                DialogInput.singleOption(BINGO_MESSAGES_KEY, mini(CrabMessages.HIGHLIGHT_TAG + "Bingo messages"), toggleOptions(current.isBingoMessages())).build(),
                DialogInput.singleOption(COORDINATE_HUD_MESSAGES_KEY, mini(CrabMessages.HIGHLIGHT_TAG + "Coordinate HUD"), toggleOptions(current.isCoordinateHud())).build()
            )).canCloseWithEscape(true).build()
        val dialog = Dialog.create { builder ->
            builder.empty().base(base).type(DialogType.confirmation(
                ActionButton.create(mini(CrabMessages.ERROR_TAG + "Cancel"), null, 100, null),
                ActionButton.create(mini(CrabMessages.SUCCESS_TAG + "Done"), null, 100,
                    DialogAction.customClick({ view, audience -> handleSubmit(uuid, view, audience) },
                        ClickCallback.Options.builder().uses(1).build()))))
        }
        player.showDialog(dialog)
    }

    /** Missing client values retain the corresponding current settings. */
    private fun handleSubmit(uuid: UUID, view: DialogResponseView?, audience: Audience) {
        if (view == null) return
        val current = settingsService.get(uuid)
        val selected = view.getText(PHANTOMS_KEY)
        val mode = if (selected != null) PhantomMode.fromId(selected) else current.getPhantomMode()
        val mentionPings = selectedToggle(view.getText(MENTION_PINGS_KEY), current.isMentionPings())
        val acceptMessages = selectedToggle(view.getText(ACCEPT_MESSAGES_KEY), current.isAcceptMessages())
        val locatorBar = selectedToggle(view.getText(LOCATOR_BAR_KEY), current.isLocatorBar())
        val bingoMessages = selectedToggle(view.getText(BINGO_MESSAGES_KEY), current.isBingoMessages())
        val coordinateHud = selectedToggle(view.getText(COORDINATE_HUD_MESSAGES_KEY), current.isCoordinateHud())
        settingsService.setAll(uuid, mode, mentionPings, acceptMessages, locatorBar, bingoMessages, coordinateHud)
        audience.sendMessage(CrabMessages.success("Settings saved."))
    }

    private fun mini(text: String): Component = CrabMessages.mini(text)

    private fun toggleOptions(enabled: Boolean): List<SingleOptionDialogInput.OptionEntry> = listOf(
        SingleOptionDialogInput.OptionEntry.create(TOGGLE_ON, mini(CrabMessages.SUCCESS_TAG + "On"), enabled),
        SingleOptionDialogInput.OptionEntry.create(TOGGLE_OFF, mini(CrabMessages.ERROR_TAG + "Off"), !enabled))

    companion object {
        private const val PHANTOMS_KEY = "phantoms"
        private const val MENTION_PINGS_KEY = "mentionPings"
        private const val ACCEPT_MESSAGES_KEY = "acceptMessages"
        private const val LOCATOR_BAR_KEY = "locatorBar"
        private const val BINGO_MESSAGES_KEY = "bingoMessages"
        private const val COORDINATE_HUD_MESSAGES_KEY = "coordinateHud"
        private const val TOGGLE_ON = "on"
        private const val TOGGLE_OFF = "off"
        private val ORDER = arrayOf(PhantomMode.ON, PhantomMode.SAFE, PhantomMode.OFF)

        private fun selectedToggle(selected: String?, fallback: Boolean): Boolean = when (selected) {
            TOGGLE_ON -> true
            TOGGLE_OFF -> false
            else -> fallback
        }
    }
}
