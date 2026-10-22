package crabcraft.net.crabUtilities.tags;

import crabcraft.net.crabUtilities.CrabMessages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class TagsCommand implements CommandExecutor, TabCompleter {
    private final TagsDialog dialog;

    public TagsCommand(TagsDialog dialog) {
        this.dialog = dialog;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) sender.sendMessage(CrabMessages.error("Only players can use /tags."));
        else if (args.length > 0) player.sendMessage(CrabMessages.error("Usage: /tags"));
        else dialog.open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
