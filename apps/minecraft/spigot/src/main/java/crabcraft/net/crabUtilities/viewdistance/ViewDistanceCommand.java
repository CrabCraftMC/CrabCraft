package crabcraft.net.crabUtilities.viewdistance;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Administrative controls for the adaptive view-distance manager. */
public final class ViewDistanceCommand {
    private static final String PERMISSION = "crabutilities.viewdistance.admin";

    private final Supplier<ViewDistanceManager> managerSupplier;

    public ViewDistanceCommand(Supplier<ViewDistanceManager> managerSupplier) {
        this.managerSupplier = managerSupplier;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(CrabMessages.error(
                    "You don't have permission to manage adaptive view distance."));
            return true;
        }

        String subcommand = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        return switch (subcommand) {
            case "status" -> sendStatus(sender, args);
            case "set" -> setDistance(sender, args);
            case "pause" -> pause(sender, args);
            case "resume" -> resume(sender, args);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(List.of("status", "set", "pause", "resume"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            return filter(List.of("view", "simulation"), args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("status")) {
            return worldNames(args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
            ViewDistanceManager manager = managerSupplier.get();
            if (manager == null) {
                return List.of();
            }
            DistanceKind kind = DistanceKind.parse(args[2]);
            if (kind == null) {
                return List.of();
            }
            List<String> distances = kind == DistanceKind.VIEW
                    ? integerRange(manager.getMinimumViewDistance(), manager.getMaximumViewDistance())
                    : integerRange(
                            manager.getMinimumSimulationDistance(),
                            manager.getMaximumSimulationDistance());
            return filter(distances, args[3]);
        }
        if (args.length == 5 && args[1].equalsIgnoreCase("set")) {
            return worldNames(args[4]);
        }
        return List.of();
    }

    private boolean sendStatus(CommandSender sender, String[] args) {
        if (args.length > 3) {
            sendUsage(sender);
            return true;
        }

        ViewDistanceManager manager = managerSupplier.get();
        sender.sendMessage(CrabMessages.accent("CrabUtilities adaptive view distance"));
        if (manager == null) {
            sender.sendMessage(CrabMessages.label(
                    "State", CrabMessages.error("disabled")));
        } else {
            sender.sendMessage(CrabMessages.label(
                    "State",
                    manager.isPaused()
                            ? CrabMessages.warning("paused")
                            : CrabMessages.success("running")));
            sender.sendMessage(CrabMessages.label(
                    "Configured simulation range",
                    manager.getMinimumSimulationDistance() + "–"
                            + manager.getMaximumSimulationDistance()));
            sender.sendMessage(CrabMessages.label(
                    "Configured view range",
                    manager.getMinimumViewDistance() + "–"
                            + manager.getMaximumViewDistance()));
        }

        List<World> worlds;
        if (args.length == 3) {
            World world = Bukkit.getWorld(args[2]);
            if (world == null) {
                sender.sendMessage(CrabMessages.error("Unknown world: " + args[2]));
                return true;
            }
            worlds = List.of(world);
        } else {
            worlds = Bukkit.getWorlds();
        }
        for (World world : worlds) {
            Component distances = CrabMessages.text("view " + world.getViewDistance())
                    .append(CrabMessages.muted(", "))
                    .append(CrabMessages.text(
                            "simulation " + world.getSimulationDistance()));
            sender.sendMessage(CrabMessages.label(world.getName(), distances));
        }
        return true;
    }

    private boolean setDistance(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 5) {
            sendUsage(sender);
            return true;
        }

        ViewDistanceManager manager = requireManager(sender);
        if (manager == null) {
            return true;
        }

        DistanceKind kind = DistanceKind.parse(args[2]);
        if (kind == null) {
            sender.sendMessage(CrabMessages.error(
                    "Distance type must be view or simulation."));
            return true;
        }

        int distance;
        try {
            distance = Integer.parseInt(args[3]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(CrabMessages.error("Distance must be a whole number."));
            return true;
        }

        int minimum = kind == DistanceKind.VIEW
                ? manager.getMinimumViewDistance()
                : manager.getMinimumSimulationDistance();
        int maximum = kind == DistanceKind.VIEW
                ? manager.getMaximumViewDistance()
                : manager.getMaximumSimulationDistance();
        if (distance < minimum || distance > maximum) {
            sender.sendMessage(CrabMessages.error(
                    kind.displayName + " distance must be between "
                            + minimum + " and " + maximum + "."));
            return true;
        }

        World world = resolveWorld(sender, args.length == 5 ? args[4] : null);
        if (world == null) {
            return true;
        }

        if (kind == DistanceKind.VIEW) {
            manager.setManualViewDistance(world, distance);
        } else {
            manager.setManualSimulationDistance(world, distance);
        }
        sender.sendMessage(CrabMessages.success(
                "Set " + world.getName() + "'s "
                        + kind.displayName + " distance to " + distance + "."));
        if (!manager.isPaused()) {
            sender.sendMessage(CrabMessages.warning(
                    "Dynamic adjustment is still running and may change this value."));
        }
        return true;
    }

    private boolean pause(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sendUsage(sender);
            return true;
        }
        ViewDistanceManager manager = requireManager(sender);
        if (manager == null) {
            return true;
        }
        if (!manager.pause()) {
            sender.sendMessage(CrabMessages.warning(
                    "Dynamic view-distance adjustment is already paused."));
            return true;
        }
        sender.sendMessage(CrabMessages.success(
                "Paused dynamic view-distance adjustment; current world values will remain fixed."));
        return true;
    }

    private boolean resume(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sendUsage(sender);
            return true;
        }
        ViewDistanceManager manager = requireManager(sender);
        if (manager == null) {
            return true;
        }
        if (!manager.resume()) {
            sender.sendMessage(CrabMessages.warning(
                    "Dynamic view-distance adjustment is already running."));
            return true;
        }
        sender.sendMessage(CrabMessages.success(
                "Resumed dynamic view-distance adjustment."));
        return true;
    }

    private ViewDistanceManager requireManager(CommandSender sender) {
        ViewDistanceManager manager = managerSupplier.get();
        if (manager == null) {
            sender.sendMessage(CrabMessages.error(
                    "Adaptive view distance is disabled or has invalid configuration."));
        }
        return manager;
    }

    private static World resolveWorld(CommandSender sender, String worldName) {
        if (worldName != null) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage(CrabMessages.error("Unknown world: " + worldName));
            }
            return world;
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        sender.sendMessage(CrabMessages.error(
                "Specify a world when running this command from console."));
        return null;
    }

    private static List<String> worldNames(String prefix) {
        return filter(Bukkit.getWorlds().stream().map(World::getName).toList(), prefix);
    }

    private static List<String> integerRange(int minimum, int maximum) {
        List<String> values = new ArrayList<>(maximum - minimum + 1);
        for (int value = minimum; value <= maximum; value++) {
            values.add(Integer.toString(value));
        }
        return values;
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalised = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalised))
                .toList();
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(CrabMessages.error(
                "Usage: /crabutilities viewdistance "
                        + "<status [world]|set <view|simulation> <distance> [world]|pause|resume>"));
    }

    private enum DistanceKind {
        VIEW("view"),
        SIMULATION("simulation");

        private final String displayName;

        DistanceKind(String displayName) {
            this.displayName = displayName;
        }

        private static DistanceKind parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "view" -> VIEW;
                case "simulation", "sim" -> SIMULATION;
                default -> null;
            };
        }
    }
}
