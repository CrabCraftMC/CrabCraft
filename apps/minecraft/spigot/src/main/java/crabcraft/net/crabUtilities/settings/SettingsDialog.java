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
    private static final String MENTION_PINGS_KEY = "mentionPings";
    private static final String ACCEPT_MESSAGES_KEY = "acceptMessages";

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
        PlayerSettings current = settingsService.get(uuid);

        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>(ORDER.length);
        for (PhantomMode mode : ORDER) {
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    mode.id(), mini(mode.coloredLabel()), mode == current.getPhantomMode()));
        }

        DialogBase base = DialogBase.builder(mini("<#FCD05C>Settings"))
                .body(List.of(DialogBody.plainMessage(mini("<#b0b0b0>Configure these just for you."))))
                .inputs(List.of(
                        DialogInput.singleOption(PHANTOMS_KEY, mini("<#FCD05C>Phantoms"), options).build(),
                        DialogInput.bool(MENTION_PINGS_KEY, mini("<#FCD05C>Mention pings"))
                                .initial(current.isMentionPings()).onTrue("On").onFalse("Off").build(),
                        DialogInput.bool(ACCEPT_MESSAGES_KEY, mini("<#FCD05C>Private messages"))
                                .initial(current.isAcceptMessages()).onTrue("On").onFalse("Off").build()))
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

        Boolean pings = view.getBoolean(MENTION_PINGS_KEY);
        Boolean accept = view.getBoolean(ACCEPT_MESSAGES_KEY);
        boolean mentionPings = pings != null ? pings : current.isMentionPings();
        boolean acceptMessages = accept != null ? accept : current.isAcceptMessages();

        settingsService.setAll(uuid, mode, mentionPings, acceptMessages);
        audience.sendMessage(mini("<#FC835C>settings saved"));
    }

    /** Deserialises a MiniMessage string with the default italic turned off. */
    private Component mini(String text) {
        return miniMessage.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }
}
