package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.update.UpdateCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ReloadCommand implements CommandExecutor, TabCompleter {

    private final CrabUtilities plugin;
    private final UpdateCommand updateCommand;

    public ReloadCommand(CrabUtilities plugin, UpdateCommand updateCommand) {
        this.plugin = plugin;
        this.updateCommand = updateCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("crabutilities.reload")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to reload CrabUtilities.");
                return true;
            }
            plugin.reloadConfig();
            plugin.getResourcePackManager().reload();
            sender.sendMessage(ChatColor.GREEN + "CrabUtilities config reloaded.");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("update") && updateCommand != null) {
            return updateCommand.handle(sender, args);
        }
        sender.sendMessage(ChatColor.RED + "Usage: /crabutilities <reload|update>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(Arrays.asList("reload", "update"));
            String p = args[0].toLowerCase();
            return opts.stream().filter(o -> o.startsWith(p)).toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("update") && updateCommand != null) {
            return updateCommand.tabComplete(args);
        }
        return Collections.emptyList();
    }
}
