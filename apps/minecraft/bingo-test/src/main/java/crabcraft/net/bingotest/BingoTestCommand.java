package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.CrabMessages;
import crabcraft.net.crabUtilities.bingo.BingoCardTwoListener;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class BingoTestCommand implements CommandExecutor, TabCompleter {
    private final BingoTestManager manager;
    private final BingoCardTwoListener listener;

    BingoTestCommand(BingoTestManager manager, BingoCardTwoListener listener) {
        this.manager = manager;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(CrabMessages.error(
                    "This detector checklist is only available in game."));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            manager.sendChecklist(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("reset") && args.length == 1) {
            listener.resetPlayer(player.getUniqueId());
            manager.reset(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("details") && args.length == 2) {
            try {
                manager.sendDetails(player, Integer.parseInt(args[1]));
            } catch (NumberFormatException exception) {
                sender.sendMessage(CrabMessages.error(
                        "Usage: /" + label + " details <1-16>"));
            }
            return true;
        }

        sender.sendMessage(CrabMessages.error(
                "Usage: /" + label + " [list|details <1-16>|reset]"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "details", "reset").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("details")) {
            return IntStream.rangeClosed(1, manager.taskCount())
                    .mapToObj(Integer::toString)
                    .filter(value -> value.startsWith(args[1]))
                    .toList();
        }
        return List.of();
    }
}
