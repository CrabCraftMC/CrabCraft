package crabcraft.net.crabUtilities.settings;

import net.kyori.adventure.text.Component;
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
            sender.sendMessage(Component.text("Only players can use /settings."));
            return true;
        }

        // Don't read or write settings until the player's record has resolved,
        // otherwise we could show/save the default over their real value.
        if (!settingsService.isLoaded(player.getUniqueId())) {
            player.sendMessage(miniMessage.deserialize(
                    "<gray>Your settings are still loading — try again in a moment.</gray>"));
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
                        "<gray>Phantoms are currently " + describe(settingsService.getPhantomMode(uuid)) + "</gray>"));
                return true;
            }
            PhantomMode mode = parseMode(args[1]);
            if (mode == null) {
                player.sendMessage(miniMessage.deserialize(
                        "<red>Usage: /settings phantoms <on|off|safe></red>"));
                return true;
            }
            settingsService.setPhantomMode(uuid, mode);
            player.sendMessage(miniMessage.deserialize(
                    "<gray>Phantoms are now " + describe(mode) + "</gray>"));
            return true;
        }

        player.sendMessage(miniMessage.deserialize(
                "<red>Usage: /settings [phantoms <on|off|safe>]</red>"));
        return true;
    }

    /** Parses a mode token strictly, returning null for unrecognised input. */
    private static PhantomMode parseMode(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true" -> PhantomMode.ON;
            case "off", "disable", "disabled", "false" -> PhantomMode.OFF;
            case "safe" -> PhantomMode.SAFE;
            default -> null;
        };
    }

    /** A short coloured MiniMessage fragment describing a mode and its effect. */
    private static String describe(PhantomMode mode) {
        return switch (mode) {
            case ON -> "<green>on</green> <gray>(phantoms spawn and attack you).</gray>";
            case OFF -> "<red>off</red> <gray>(no phantoms spawn near or attack you).</gray>";
            case SAFE -> "<yellow>safe</yellow> <gray>(phantoms spawn but won't attack you).</gray>";
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
