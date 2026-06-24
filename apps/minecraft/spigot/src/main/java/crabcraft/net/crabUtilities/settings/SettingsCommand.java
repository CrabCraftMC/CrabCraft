package crabcraft.net.crabUtilities.settings;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /settings} — opens the per-player settings menu, or configures a
 * setting directly from chat.
 *
 * <ul>
 *   <li>{@code /settings} — opens the settings dialog (players only).</li>
 *   <li>{@code /settings phantoms} — shows the current phantom preference.</li>
 *   <li>{@code /settings phantoms <on|off|toggle>} — changes it.</li>
 * </ul>
 *
 * <p>Settings are per-player, so every path requires a player sender.
 */
public class SettingsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("phantoms");
    private static final List<String> PHANTOM_VALUES = List.of("on", "off", "toggle");

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
        // otherwise we could show/save the default OFF over their real value.
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
                boolean enabled = settingsService.isPhantomsEnabled(uuid);
                player.sendMessage(miniMessage.deserialize(enabled
                        ? "<gray>Phantoms are currently <green>enabled</green> for you.</gray>"
                        : "<gray>Phantoms are currently <red>disabled</red> for you.</gray>"));
                return true;
            }
            Boolean target = switch (args[1].toLowerCase()) {
                case "on", "enable", "enabled", "true" -> Boolean.TRUE;
                case "off", "disable", "disabled", "false" -> Boolean.FALSE;
                case "toggle" -> !settingsService.isPhantomsEnabled(uuid);
                default -> null;
            };
            if (target == null) {
                player.sendMessage(miniMessage.deserialize(
                        "<red>Usage: /settings phantoms <on|off|toggle></red>"));
                return true;
            }
            settingsService.setPhantomsEnabled(uuid, target);
            sendPhantomState(player, target);
            return true;
        }

        player.sendMessage(miniMessage.deserialize(
                "<red>Usage: /settings [phantoms <on|off|toggle>]</red>"));
        return true;
    }

    private void sendPhantomState(Player player, boolean enabled) {
        player.sendMessage(miniMessage.deserialize(enabled
                ? "<gray>Phantoms are now <green>enabled</green> for you.</gray>"
                : "<gray>Phantoms are now <red>disabled</red> for you.</gray>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("phantoms")) {
            return PHANTOM_VALUES.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
