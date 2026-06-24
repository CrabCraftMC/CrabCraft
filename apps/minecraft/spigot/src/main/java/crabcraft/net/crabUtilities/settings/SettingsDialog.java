package crabcraft.net.crabUtilities.settings;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The {@code /settings} screen, built with Paper's server-side Dialog API
 * (Minecraft 1.21.6+ / Paper 1.21.7+) rather than a chest GUI.
 *
 * <p>The dialog shows one boolean toggle per configurable feature (currently
 * just phantoms), pre-filled with the player's current preference. Pressing
 * <em>Done</em> submits every toggle's value through a custom-click callback,
 * which writes the changes via {@link PlayerSettingsService}; pressing Escape
 * dismisses the screen without saving. More toggles can be added by appending
 * inputs and reading their keys in {@link #handleSubmit}.
 */
@SuppressWarnings("UnstableApiUsage") // Paper Dialog API is @ApiStatus.Experimental
public class SettingsDialog {

    private static final String PHANTOMS_KEY = "phantoms";

    private final PlayerSettingsService settingsService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public SettingsDialog(PlayerSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Builds a fresh dialog reflecting the player's current settings and shows it. */
    public void open(Player player) {
        UUID uuid = player.getUniqueId();
        boolean phantomsEnabled = settingsService.isPhantomsEnabled(uuid);

        DialogBase base = DialogBase.builder(miniMessage.deserialize("<dark_aqua><bold>Settings</bold></dark_aqua>"))
                .canCloseWithEscape(true)
                .body(List.of(DialogBody.plainMessage(miniMessage.deserialize(
                        "<gray>Toggle features for yourself. Changes apply across the network.</gray>"))))
                .inputs(List.of(DialogInput.bool(PHANTOMS_KEY, miniMessage.deserialize("<white>Phantoms</white>"))
                        .initial(phantomsEnabled)
                        .onTrue("Enabled")
                        .onFalse("Disabled")
                        .build()))
                .build();

        ActionButton done = ActionButton.builder(miniMessage.deserialize("<green>Done</green>"))
                .tooltip(miniMessage.deserialize("<gray>Save your settings</gray>"))
                .action(DialogAction.customClick(
                        (view, audience) -> handleSubmit(uuid, view, audience),
                        ClickCallback.Options.builder().uses(1).build()))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.notice(done)));

        player.showDialog(dialog);
    }

    /**
     * Applies every toggle the player submitted. Reads each input from the
     * response view (absent/unknown keys are skipped) and writes through the
     * settings service, which mirrors the change to Redis and re-applies the
     * phantom effect immediately.
     */
    private void handleSubmit(UUID uuid, DialogResponseView view, Audience audience) {
        if (view == null) {
            return;
        }
        Boolean phantoms = view.getBoolean(PHANTOMS_KEY);
        if (phantoms != null) {
            settingsService.setPhantomsEnabled(uuid, phantoms);
            audience.sendMessage(miniMessage.deserialize(phantoms
                    ? "<gray>Phantoms are now <green>enabled</green> for you.</gray>"
                    : "<gray>Phantoms are now <red>disabled</red> for you.</gray>"));
        }
    }
}
