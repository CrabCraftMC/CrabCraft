package crabcraft.net.crabUtilities.coordinates;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * {@code /portalcoords} — Translate coordinates for portals between the overworld and nether
 *
 * <ul>
 *   <li>{@code /portalcoords} — Converts player's current coordinates to opposite dimension's coordinate</li>
 *   <li>{@code /portalcoords at [x] [z] in [overworld|nether]} — Converts coordinates provided by player to another dimension's coordinate</li>
 * </ul>
 */
public class CoordinateHelperCommand implements CommandExecutor, TabCompleter {
    private enum CoordinateDimension {
        OVERWORLD,
        NETHER;

        public static CoordinateDimension getPlayerDimension(Player player) {
            if (player.getWorld().getEnvironment() == World.Environment.NETHER) {
                return CoordinateDimension.NETHER;
            }
            return CoordinateDimension.OVERWORLD; // We treat End coordinates as overworld coordinates
        }

        public int convertToOtherCoordinate(int coordinate) {
            if (this == OVERWORLD) {
                return Math.floorDiv(coordinate, 8);
            }
            return coordinate * 8;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] args) {
        if (!(commandSender instanceof Player player)) {
            commandSender.sendMessage(CrabMessages.error("Only players can use /portalcoords."));
            return true;
        }

        if (args.length == 0) {
            int x = player.getLocation().getBlockX();
            int z = player.getLocation().getBlockZ();
            handleForCoordinates(player, x, z, CoordinateDimension.getPlayerDimension(player));

            return true;
        }

        if (args[0].toLowerCase(Locale.ROOT).equals("at")) {
            handleAtCommand(player, args);
        } else {
            sendUsage(player);
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender commandSender, @NotNull Command command, @NotNull String s, @NotNull String @NotNull [] strings) {
        if (strings.length == 1) {
            return filterStartsWith(strings[0], List.of("at"));
        }

        if (strings.length == 2 && strings[0].equalsIgnoreCase("at")) {
            return filterStartsWith(strings[1], List.of("<x>"));
        }

        if (strings.length == 3 && strings[0].equalsIgnoreCase("at")) {
            return filterStartsWith(strings[2], List.of("<z>"));
        }

        if (strings.length == 4 && strings[0].equalsIgnoreCase("at")) {
            // Suggest "in overworld" / "in nether" as single two-word completions
            return filterStartsWith(strings[3], List.of("in overworld", "in nether"));
        }

        if (strings.length == 5 && strings[0].equalsIgnoreCase("at") && strings[3].equalsIgnoreCase("in")) {
            // Fallback: player already typed "in " manually and is now completing the dimension
            return filterStartsWith(strings[4], List.of("overworld", "nether"));
        }

        return List.of();
    }

    private void handleAtCommand(Player player, String[] args) {
        // Expected: at [x] [z] in [overworld|nether]
        if (args.length != 5 || !args[3].equalsIgnoreCase("in")) {
            sendUsage(player);
            return;
        }

        int x;
        int z;
        try {
            x = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sendUsage(player);
            return;
        }

        CoordinateDimension dimension;
        switch (args[4].toLowerCase(Locale.ROOT)) {
            case "overworld" -> dimension = CoordinateDimension.OVERWORLD;
            case "nether" -> dimension = CoordinateDimension.NETHER;
            default -> {
                sendUsage(player);
                return;
            }
        }

        handleForCoordinates(player, x, z, dimension);
    }

    private List<String> filterStartsWith(String prefix, List<String> options) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.startsWith(lowerPrefix))
                .toList();
    }

    private void handleForCoordinates(Player player, int x, int z, CoordinateDimension dimension) {
        int otherX = dimension.convertToOtherCoordinate(x);
        int otherZ = dimension.convertToOtherCoordinate(z);

        Component overworld = Component.text(" in the Overworld ", CrabMessages.SUCCESS);
        Component nether = Component.text(" in the Nether ", CrabMessages.ERROR);

        Component formattedResponse = Component.text()
                .append(Component.text(x + " " + z, CrabMessages.HIGHLIGHT))
                .append(dimension == CoordinateDimension.OVERWORLD ? overworld : nether)
                .append(Component.text("is ", CrabMessages.TEXT))
                .append(Component.text(otherX + " " + otherZ, CrabMessages.HIGHLIGHT))
                .append(dimension == CoordinateDimension.OVERWORLD ? nether : overworld)
                .append(Component.text("[Click to copy]")
                        .decorate(TextDecoration.ITALIC)
                        .color(CrabMessages.HIGHLIGHT)
                        .clickEvent(ClickEvent.copyToClipboard(otherX + " " + otherZ)))
                .build();
        player.sendMessage(formattedResponse);
    }

    private void sendUsage(Player player) {
        player.sendMessage(CrabMessages.error(
                "Usage: /portalcoords at [x] [z] in [overworld|nether]"));
    }
}