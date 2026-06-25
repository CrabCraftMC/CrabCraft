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
 * <p>Phantoms are configured with a single-option selector (On / Safe / Off)
 * pre-filled with the player's current {@link PhantomMode}. Pressing
 * <em>Done</em> submits the choice through a custom-click callback that writes
 * it via {@link PlayerSettingsService}; Escape dismisses without saving. More
 * settings can be added by appending inputs and reading their keys in
 * {@link #handleSubmit}.
 */
@SuppressWarnings("UnstableApiUsage") // Paper Dialog API is @ApiStatus.Experimental
public class SettingsDialog {

    private static final String PHANTOMS_KEY = "phantoms";

    /** Order shown in the selector (most phantoms → least). */
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
                    mode.id(), optionLabel(mode), mode == current));
        }

        DialogBase base = DialogBase.builder(
                        noItalic(miniMessage.deserialize("<dark_aqua><bold>Settings</bold></dark_aqua>")))
                .canCloseWithEscape(true)
                .body(List.of(DialogBody.plainMessage(noItalic(miniMessage.deserialize(
                        "<gray>Phantoms — choose how they behave for you:</gray>"
                                + "<newline><green>On</green><gray>: spawn and attack.</gray>"
                                + "<newline><yellow>Safe</yellow><gray>: spawn, but never attack you.</gray>"
                                + "<newline><red>Off</red><gray>: never spawn near or attack you.</gray>")))))
                .inputs(List.of(DialogInput.singleOption(PHANTOMS_KEY,
                        noItalic(miniMessage.deserialize("<white>Phantoms</white>")), options).build()))
                .build();

        ActionButton done = ActionButton.builder(noItalic(miniMessage.deserialize("<green>Done</green>")))
                .tooltip(noItalic(miniMessage.deserialize("<gray>Save your settings</gray>")))
                .action(DialogAction.customClick(
                        (view, audience) -> handleSubmit(uuid, view, audience),
                        ClickCallback.Options.builder().uses(1).build()))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.notice(done)));

        player.showDialog(dialog);
    }

    private Component optionLabel(PhantomMode mode) {
        return noItalic(miniMessage.deserialize(switch (mode) {
            case ON -> "<green>On</green>";
            case SAFE -> "<yellow>Safe</yellow>";
            case OFF -> "<red>Off</red>";
        }));
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
        audience.sendMessage(noItalic(miniMessage.deserialize(switch (mode) {
            case ON -> "<gray>Phantoms are now <green>on</green> for you.</gray>";
            case SAFE -> "<gray>Phantoms are now <yellow>safe</yellow> — they spawn but won't attack you.</gray>";
            case OFF -> "<gray>Phantoms are now <red>off</red> for you.</gray>";
        })));
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
