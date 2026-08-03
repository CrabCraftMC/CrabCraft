package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabMessages;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
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
 * <p>Colours follow the shared CrabCraft palette used by the media feature: gold
 * {@code #FCD05C} titles/labels, green {@code #77dd77} confirm, red
 * {@code #f77069} cancel, and off-white {@code #F4F1EA} body text.
 */
@SuppressWarnings("UnstableApiUsage") // Paper Dialog API is @ApiStatus.Experimental
public class SettingsDialog {

    private static final String PHANTOMS_KEY = "phantoms";
    private static final String MENTION_PINGS_KEY = "mentionPings";
    private static final String ACCEPT_MESSAGES_KEY = "acceptMessages";
    private static final String LOCATOR_BAR_KEY = "locatorBar";
    private static final String BINGO_MESSAGES_KEY = "bingoMessages";
    private static final String TOGGLE_ON = "on";
    private static final String TOGGLE_OFF = "off";

    /** Order shown in the selector (most phantoms to least). */
    private static final PhantomMode[] ORDER = { PhantomMode.ON, PhantomMode.SAFE, PhantomMode.OFF };

    private final PlayerSettingsService settingsService;

    public SettingsDialog(PlayerSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Builds a fresh dialog reflecting the player's current settings and shows it. */
    public void open(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSettings current = settingsService.get(uuid);

        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>(ORDER.length);
        for (PhantomMode mode : ORDER) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    mode.id(), mini(mode.coloredLabel()), mode == current.getPhantomMode()));
        }

        DialogBase base = DialogBase.builder(
                        mini(CrabMessages.HIGHLIGHT_TAG + "Settings"))
                .inputs(List.of(
                        DialogInput.singleOption(PHANTOMS_KEY,
                                mini(CrabMessages.HIGHLIGHT_TAG + "Phantoms"), options).build(),
                        DialogInput.singleOption(MENTION_PINGS_KEY,
                                mini(CrabMessages.HIGHLIGHT_TAG + "Chat pings"),
                                toggleOptions(current.isMentionPings())).build(),
                        DialogInput.singleOption(ACCEPT_MESSAGES_KEY,
                                mini(CrabMessages.HIGHLIGHT_TAG + "Private messages"),
                                toggleOptions(current.isAcceptMessages())).build(),
                        DialogInput.singleOption(LOCATOR_BAR_KEY,
                                mini(CrabMessages.HIGHLIGHT_TAG + "Locator bar"),
                                toggleOptions(current.isLocatorBar())).build(),
                        DialogInput.singleOption(BINGO_MESSAGES_KEY,
                                mini(CrabMessages.HIGHLIGHT_TAG + "Bingo messages"),
                                toggleOptions(current.isBingoMessages())).build()))
                .canCloseWithEscape(true)
                .build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(base)
                .type(DialogType.confirmation(
                        ActionButton.create(
                                mini(CrabMessages.ERROR_TAG + "Cancel"), null, 100, null),
                        ActionButton.create(
                                mini(CrabMessages.SUCCESS_TAG + "Done"), null, 100,
                                DialogAction.customClick(
                                        (view, audience) -> handleSubmit(uuid, view, audience),
                                        ClickCallback.Options.builder().uses(1).build())))));

        player.showDialog(dialog);
    }

    /**
     * Applies every submitted setting at once. Any value the client omits keeps
     * the player's current value, so the dialog can never blank a setting out.
     */
    private void handleSubmit(UUID uuid, DialogResponseView view, Audience audience) {
        if (view == null) {
            return;
        }
        PlayerSettings current = settingsService.get(uuid);

        String selected = view.getText(PHANTOMS_KEY);
        PhantomMode mode = selected != null ? PhantomMode.fromId(selected) : current.getPhantomMode();

        boolean mentionPings = selectedToggle(view.getText(MENTION_PINGS_KEY), current.isMentionPings());
        boolean acceptMessages = selectedToggle(view.getText(ACCEPT_MESSAGES_KEY), current.isAcceptMessages());
        boolean locatorBar = selectedToggle(view.getText(LOCATOR_BAR_KEY), current.isLocatorBar());
        boolean bingoMessages = selectedToggle(
                view.getText(BINGO_MESSAGES_KEY), current.isBingoMessages());

        settingsService.setAll(uuid, mode, mentionPings, acceptMessages, locatorBar, bingoMessages);
        audience.sendMessage(CrabMessages.success("Settings saved."));
    }

    /** Deserialises a MiniMessage string with the default italic turned off. */
    private Component mini(String text) {
        return CrabMessages.mini(text);
    }

    private List<SingleOptionDialogInput.OptionEntry> toggleOptions(boolean enabled) {
        return List.of(
                SingleOptionDialogInput.OptionEntry.create(
                        TOGGLE_ON, mini(CrabMessages.SUCCESS_TAG + "On"), enabled),
                SingleOptionDialogInput.OptionEntry.create(
                        TOGGLE_OFF, mini(CrabMessages.ERROR_TAG + "Off"), !enabled));
    }

    private static boolean selectedToggle(String selected, boolean fallback) {
        if (TOGGLE_ON.equals(selected)) {
            return true;
        }
        if (TOGGLE_OFF.equals(selected)) {
            return false;
        }
        return fallback;
    }
}
