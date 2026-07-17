package crabcraft.net.crabUtilities.slime;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
            sender.sendMessage(Component.text("Only players can use /slime.", NamedTextColor.RED));
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

    private static void openMap(Player player) {
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
        NamedTextColor color = slimeChunk ? NamedTextColor.GREEN : NamedTextColor.RED;
        String message = slimeChunk
                ? "You are standing in a slime chunk "
                : "You are not standing in a slime chunk ";

        player.sendMessage(Component.text(message, color)
                .append(Component.text("(" + chunk.getX() + ", " + chunk.getZ() + ")", NamedTextColor.GOLD))
                .append(Component.text(".", color)));
    }

    private static void sendUsage(Player player) {
        player.sendMessage(Component.text("Usage: ", NamedTextColor.RED)
                .append(Component.text("/slime <map|chunk>", NamedTextColor.GOLD)));
    }

    private static void sendNoPermission(Player player) {
        player.sendMessage(Component.text("You do not have permission to use that slime command.",
                NamedTextColor.RED));
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
