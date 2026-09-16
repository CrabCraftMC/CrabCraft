package crabcraft.net.crabUtilities.bingo;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/** Shows the requesting player's weekly card without broadcasting it. */
public final class BingoCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SQUARES = IntStream.range(0, 16)
            .mapToObj(BingoChatView::coordinate).toList();
    private final BingoManager manager;

    public BingoCommand(BingoManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use /bingo."));
            return true;
        }
        int square = args.length == 1 ? SQUARES.indexOf(args[0].toUpperCase(Locale.ROOT)) : -1;
        if (args.length > 1 || (args.length == 1 && square < 0)) {
            player.sendMessage(Component.text("Usage: /bingo [A1-D4]"));
            return true;
        }
        manager.showCard(player, square);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toUpperCase(Locale.ROOT);
        return SQUARES.stream().filter(square -> square.startsWith(prefix)).toList();
    }
}
