package crabcraft.net.crabUtilities.slime;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** Provides the player-facing {@code /slime map} and {@code /slime chunk} commands. */
public final class SlimeCommand implements CommandExecutor, TabCompleter {

    private static final String MAP_PERMISSION = "crabutilities.slime.map";
    private static final String CHUNK_PERMISSION = "crabutilities.slime.chunk";
    private static final List<String> SUBCOMMANDS = List.of("map", "chunk");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(CrabMessages.error("Only players can use /slime."));
            return true;
        }

        if (args.length != 1) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "map" -> openMap(player);
            case "chunk" -> checkChunk(player);
            default -> sendUsage(player);
        }
        return true;
    }

    static void openMap(Player player) {
        if (!player.hasPermission(MAP_PERMISSION)) {
            sendNoPermission(player);
            return;
        }
        SlimeMap.open(player);
    }

    private static void checkChunk(Player player) {
        if (!player.hasPermission(CHUNK_PERMISSION)) {
            sendNoPermission(player);
            return;
        }

        Chunk chunk = player.getChunk();
        boolean slimeChunk = chunk.isSlimeChunk();
        String message = slimeChunk
                ? "You are standing in a slime chunk "
                : "You are not standing in a slime chunk ";

        Component result = slimeChunk
                ? CrabMessages.success(message)
                : CrabMessages.error(message);
        player.sendMessage(result
                .append(CrabMessages.highlight(
                        "(" + chunk.getX() + ", " + chunk.getZ() + ")"))
                .append(slimeChunk
                        ? CrabMessages.success(".")
                        : CrabMessages.error(".")));
    }

    private static void sendUsage(Player player) {
        player.sendMessage(CrabMessages.error("Usage: ")
                .append(CrabMessages.highlight("/slime <map|chunk>")));
    }

    private static void sendNoPermission(Player player) {
        player.sendMessage(CrabMessages.error(
                "You do not have permission to use that slime command."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
