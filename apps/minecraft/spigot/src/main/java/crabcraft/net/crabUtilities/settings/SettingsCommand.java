package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /settings} — opens the per-player settings dialog, or configures a
 * setting directly from chat.
 *
 * <ul>
 *   <li>{@code /settings} — opens the settings dialog (players only).</li>
 *   <li>{@code /settings phantoms [on|off|safe]}</li>
 *   <li>{@code /settings mentions [on|off]} — chat mention ping sound.</li>
 *   <li>{@code /settings messages [on|off]} — accept private messages.</li>
 *   <li>{@code /settings locatorbar [on|off]} — vanilla locator bar.</li>
 *   <li>{@code /settings bingo [on|off]} — personal bingo completion messages.</li>
 * </ul>
 *
 * <p>Settings are per-player, so every path requires a player sender.
 */
public class SettingsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("phantoms", "mentions", "messages", "locatorbar", "bingo");
    private static final List<String> PHANTOM_VALUES = List.of("on", "off", "safe");
    private static final List<String> TOGGLE_VALUES = List.of("on", "off");

    private final PlayerSettingsService settingsService;
    private final SettingsDialog dialog;

    public SettingsCommand(PlayerSettingsService settingsService, SettingsDialog dialog) {
        this.settingsService = settingsService;
        this.dialog = dialog;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(CrabMessages.error("Only players can use /settings."));
            return true;
        }

        // Don't read or write settings until the player's record has resolved,
        // otherwise we could show/save the default over their real value.
        UUID uuid = player.getUniqueId();
        if (!settingsService.isLoaded(uuid)) {
            player.sendMessage(CrabMessages.muted(
                    "Your settings are still loading, try again in a moment."));
            return true;
        }

        if (args.length == 0) {
            dialog.open(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "phantoms" -> handlePhantoms(player, uuid, args);
            case "mentions" -> handleToggle(player, args, "Chat pings",
                    settingsService.isMentionPingsEnabled(uuid),
                    value -> settingsService.setMentionPings(uuid, value));
            case "messages" -> handleToggle(player, args, "Private messages",
                    settingsService.isAcceptingMessages(uuid),
                    value -> settingsService.setAcceptingMessages(uuid, value));
            case "locatorbar", "locator-bar", "locator" -> handleToggle(player, args, "Locator bar",
                    settingsService.isLocatorBarEnabled(uuid),
                    value -> settingsService.setLocatorBar(uuid, value));
            case "bingo" -> handleToggle(player, args, "Bingo messages",
                    settingsService.isBingoMessagesEnabled(uuid),
                    value -> settingsService.setBingoMessages(uuid, value));
            default -> player.sendMessage(CrabMessages.error(
                    "Usage: /settings [phantoms|mentions|messages|locatorbar|bingo] ..."));
        }
        return true;
    }

    private void handlePhantoms(Player player, UUID uuid, String[] args) {
        if (args.length == 1) {
            player.sendMessage(CrabMessages.label(
                    "Phantoms",
                    CrabMessages.mini(
                            settingsService.getPhantomMode(uuid).coloredLabel())));
            return;
        }
        PhantomMode mode = parseMode(args[1]);
        if (mode == null) {
            player.sendMessage(CrabMessages.error(
                    "Usage: /settings phantoms <on|off|safe>"));
            return;
        }
        settingsService.setPhantomMode(uuid, mode);
        player.sendMessage(CrabMessages.success("Settings saved."));
    }

    private void handleToggle(Player player, String[] args, String label, boolean currentValue,
                              java.util.function.Consumer<Boolean> setter) {
        if (args.length == 1) {
            player.sendMessage(CrabMessages.label(label, onOff(currentValue)));
            return;
        }
        Boolean value = parseToggle(args[1]);
        if (value == null) {
            player.sendMessage(CrabMessages.error(
                    "Usage: /settings "
                            + args[0].toLowerCase(Locale.ROOT)
                            + " <on|off>"));
            return;
        }
        setter.accept(value);
        player.sendMessage(CrabMessages.success("Settings saved."));
    }

    /** Parses a phantom mode token strictly, returning null for unrecognised input. */
    private static PhantomMode parseMode(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true" -> PhantomMode.ON;
            case "off", "disable", "disabled", "false" -> PhantomMode.OFF;
            case "safe", "dontattack", "dont-attack", "noattack", "no-attack" -> PhantomMode.SAFE;
            default -> null;
        };
    }

    /** Parses an on/off token, returning null for unrecognised input. */
    private static Boolean parseToggle(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true", "yes" -> Boolean.TRUE;
            case "off", "disable", "disabled", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static Component onOff(boolean value) {
        return value ? CrabMessages.success("On") : CrabMessages.error("Off");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            List<String> values = args[0].equalsIgnoreCase("phantoms") ? PHANTOM_VALUES
                    : (args[0].equalsIgnoreCase("mentions") || args[0].equalsIgnoreCase("messages")
                            || args[0].equalsIgnoreCase("locatorbar") || args[0].equalsIgnoreCase("locator")
                            || args[0].equalsIgnoreCase("locator-bar")
                            || args[0].equalsIgnoreCase("bingo"))
                        ? TOGGLE_VALUES : List.of();
            return values.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
