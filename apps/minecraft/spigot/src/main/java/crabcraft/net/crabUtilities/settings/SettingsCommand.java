package crabcraft.net.crabUtilities.settings;

import net.kyori.adventure.text.minimessage.MiniMessage;
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
 *   <li>{@code /settings phantoms} — shows the current phantom mode.</li>
 *   <li>{@code /settings phantoms <on|off|safe>} — changes it.</li>
 * </ul>
 *
 * <p>Settings are per-player, so every path requires a player sender.
 */
public class SettingsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("phantoms");
    private static final List<String> PHANTOM_VALUES = List.of("on", "off", "safe");

    private final PlayerSettingsService settingsService;
    private final SettingsDialog dialog;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public SettingsCommand(PlayerSettingsService settingsService, SettingsDialog dialog) {
        this.settingsService = settingsService;
        this.dialog = dialog;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(miniMessage.deserialize("<#f77069>Only players can use /settings."));
            return true;
        }

        // Don't read or write settings until the player's record has resolved,
        // otherwise we could show/save the default over their real value.
        if (!settingsService.isLoaded(player.getUniqueId())) {
            player.sendMessage(miniMessage.deserialize(
                    "<#b0b0b0>Your settings are still loading, try again in a moment."));
            return true;
        }

        if (args.length == 0) {
            dialog.open(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("phantoms")) {
            UUID uuid = player.getUniqueId();
            if (args.length == 1) {
                player.sendMessage(miniMessage.deserialize(
                        "<#FC835C>Phantoms: " + settingsService.getPhantomMode(uuid).coloredLabel()));
                return true;
            }
            PhantomMode mode = parseMode(args[1]);
            if (mode == null) {
                player.sendMessage(miniMessage.deserialize(
                        "<#f77069>Usage: /settings phantoms <on|off|safe>"));
                return true;
            }
            settingsService.setPhantomMode(uuid, mode);
            player.sendMessage(miniMessage.deserialize(
                    "<#FC835C>Phantoms set to " + mode.coloredLabel() + "<#FC835C>."));
            return true;
        }

        player.sendMessage(miniMessage.deserialize(
                "<#f77069>Usage: /settings [phantoms <on|off|safe>]"));
        return true;
    }

    /** Parses a mode token strictly, returning null for unrecognised input. */
    private static PhantomMode parseMode(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true" -> PhantomMode.ON;
            case "off", "disable", "disabled", "false" -> PhantomMode.OFF;
            case "safe", "dontattack", "dont-attack", "noattack", "no-attack" -> PhantomMode.SAFE;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("phantoms")) {
            return PHANTOM_VALUES.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
