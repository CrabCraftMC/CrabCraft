package crabcraft.net.crabUtilities.settings;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The {@code /settings} screen, built with Paper's server-side Dialog API
 * (Minecraft 1.21.6+ / Paper 1.21.7+).
 *
 * <p>Phantoms are configured with a single-option selector whose options are
 * self-describing (On / Don't attack / Off), so no per-option text is needed.
 * Pressing <em>Done</em> submits the choice through a custom-click callback
 * that writes it via {@link PlayerSettingsService}; <em>Cancel</em> or Escape
 * dismisses without saving. More settings can be added by appending inputs and
 * reading their keys in {@link #handleSubmit}.
 *
 * <p>Colours follow the shared CrabCraft palette (matching CustomDiscs): gold
 * {@code #FCD05C} titles/labels, green {@code #77dd77} confirm, red
 * {@code #f77069} cancel, grey {@code #b0b0b0} body.
 */
@SuppressWarnings("UnstableApiUsage") // Paper Dialog API is @ApiStatus.Experimental
public class SettingsDialog {

    private static final String PHANTOMS_KEY = "phantoms";

    /** Order shown in the selector (most phantoms to least). */
    private static final PhantomMode[] ORDER = { PhantomMode.ON, PhantomMode.SAFE, PhantomMode.OFF };

    private final PlayerSettingsService settingsService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public SettingsDialog(PlayerSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Builds a fresh dialog reflecting the player's current settings and shows it. */
    public void open(Player player) {
        UUID uuid = player.getUniqueId();
        PhantomMode current = settingsService.getPhantomMode(uuid);

        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>(ORDER.length);
        for (PhantomMode mode : ORDER) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    mode.id(), mini(mode.coloredLabel()), mode == current));
        }

        DialogBase base = DialogBase.builder(mini("<#FCD05C>Settings"))
                .body(List.of(DialogBody.plainMessage(mini("<#b0b0b0>Choose how phantoms behave for you."))))
                .inputs(List.of(DialogInput.singleOption(PHANTOMS_KEY, mini("<#FCD05C>Phantoms"), options).build()))
                .canCloseWithEscape(true)
                .build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(base)
                .type(DialogType.confirmation(
                        ActionButton.create(mini("<#f77069>Cancel"), null, 100, null),
                        ActionButton.create(mini("<#77dd77>Done"), null, 100,
                                DialogAction.customClick(
                                        (view, audience) -> handleSubmit(uuid, view, audience),
                                        ClickCallback.Options.builder().uses(1).build())))));

        player.showDialog(dialog);
    }

    /**
     * Applies the submitted phantom mode. Reads the selected option id from the
     * response view (absent/unknown falls back to the safe default) and writes
     * it through the settings service.
     */
    private void handleSubmit(UUID uuid, DialogResponseView view, Audience audience) {
        if (view == null) {
            return;
        }
        String selected = view.getText(PHANTOMS_KEY);
        if (selected == null) {
            return;
        }
        PhantomMode mode = PhantomMode.fromId(selected);
        settingsService.setPhantomMode(uuid, mode);
        audience.sendMessage(mini("<#FC835C>Phantoms set to " + mode.coloredLabel() + "<#FC835C>."));
    }

    /** Deserialises a MiniMessage string with the default italic turned off. */
    private Component mini(String text) {
        return miniMessage.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }
}
