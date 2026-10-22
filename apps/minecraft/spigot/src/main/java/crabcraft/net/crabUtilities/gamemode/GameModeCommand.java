package crabcraft.net.crabUtilities.gamemode;

import crabcraft.net.crabUtilities.CrabMessages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Game-mode shortcuts with a separate permission for each destination mode. */
public final class GameModeCommand implements CommandExecutor, TabCompleter {

    private static final String OTHERS_PERMISSION = "crabutilities.gamemode.others";
    private static final List<String> MODE_NAMES = List.of(
            "survival", "creative", "adventure", "spectator");

    private final Supplier<Collection<? extends Player>> onlinePlayers;

    public GameModeCommand() {
        this(Bukkit::getOnlinePlayers);
    }

    GameModeCommand(Supplier<Collection<? extends Player>> onlinePlayers) {
        this.onlinePlayers = onlinePlayers;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        GameMode shortcut = shortcutMode(command.getName());
        int targetIndex = shortcut == null ? 1 : 0;
        if (args.length < targetIndex || args.length > targetIndex + 1) {
            sendUsage(sender, label, shortcut);
            return true;
        }

        GameMode mode = shortcut == null ? parseMode(args[0]) : shortcut;
        if (mode == null) {
            sender.sendMessage(CrabMessages.error(
                    "Choose survival, creative, adventure or spectator."));
            return true;
        }
        if (!sender.hasPermission(permission(mode))) {
            sender.sendMessage(CrabMessages.error(
                    "You don't have permission to use " + modeName(mode) + " mode."));
            return true;
        }

        Player target;
        if (args.length > targetIndex) {
            target = onlinePlayers.get().stream()
                    .filter(player -> player.getName().equalsIgnoreCase(args[targetIndex]))
                    .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                    .findFirst().orElse(null);
            if (target == null) {
                sender.sendMessage(CrabMessages.error("That player is not online."));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(CrabMessages.error("Specify an online player from the console."));
            return true;
        }

        boolean self = sender instanceof Player player
                && player.getUniqueId().equals(target.getUniqueId());
        if (!self && !sender.hasPermission(OTHERS_PERMISSION)) {
            sender.sendMessage(CrabMessages.error(
                    "You don't have permission to change another player's game mode."));
            return true;
        }

        target.setGameMode(mode);
        if (target.getGameMode() != mode) {
            sender.sendMessage(CrabMessages.error("The game-mode change was cancelled."));
            return true;
        }
        target.sendMessage(CrabMessages.success("Your game mode is now " + modeName(mode) + "."));
        if (!self) {
            sender.sendMessage(CrabMessages.success("Set ").append(target.displayName())
                    .append(CrabMessages.success("'s game mode to " + modeName(mode) + ".")));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        GameMode shortcut = shortcutMode(command.getName());
        if (shortcut == null && args.length == 1) {
            return MODE_NAMES.stream()
                    .filter(name -> sender.hasPermission(permission(parseMode(name))))
                    .filter(name -> startsWith(name, args[0]))
                    .toList();
        }
        int targetIndex = shortcut == null ? 1 : 0;
        if (args.length != targetIndex + 1) {
            return List.of();
        }
        GameMode mode = shortcut == null ? parseMode(args[0]) : shortcut;
        if (mode == null || !sender.hasPermission(permission(mode))) {
            return List.of();
        }
        return onlinePlayers.get().stream()
                .filter(player -> sender.hasPermission(OTHERS_PERMISSION)
                        || sender instanceof Player viewer
                        && viewer.getUniqueId().equals(player.getUniqueId()))
                .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                .map(Player::getName)
                .filter(name -> startsWith(name, args[targetIndex]))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static GameMode shortcutMode(String command) {
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "gms", "survival" -> GameMode.SURVIVAL;
            case "gmc", "creative" -> GameMode.CREATIVE;
            case "gma", "adventure" -> GameMode.ADVENTURE;
            case "gmsp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static GameMode parseMode(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "survival", "s", "0" -> GameMode.SURVIVAL;
            case "creative", "c", "1" -> GameMode.CREATIVE;
            case "adventure", "a", "2" -> GameMode.ADVENTURE;
            case "spectator", "sp", "3" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static String permission(GameMode mode) {
        return "crabutilities.gamemode." + modeName(mode);
    }

    private static String modeName(GameMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static void sendUsage(CommandSender sender, String label, GameMode shortcut) {
        sender.sendMessage(CrabMessages.error("Usage: /" + label
                + (shortcut == null ? " <survival|creative|adventure|spectator>" : "")
                + " [player]"));
    }
}
