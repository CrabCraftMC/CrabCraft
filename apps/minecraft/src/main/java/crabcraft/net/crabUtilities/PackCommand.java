package crabcraft.net.crabUtilities;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PackCommand implements CommandExecutor, TabCompleter {
    private final CrabUtilities plugin;

    public PackCommand(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("Usage: /" + label + " <on|off>");
            return true;
        }

        String sub = args[0].toLowerCase();
        ResourcePackManager rpm = plugin.getResourcePackManager();

        switch (sub) {
            case "on":
                if (!rpm.isConfigured()) {
                    sender.sendMessage("Resource pack URL is not configured. Ask an admin to set resource-pack.url in config.yml.");
                    return true;
                }
                boolean sent = rpm.enableFor(player);
                if (sent) {
                    sender.sendMessage("Resource pack enabled. You'll receive it on join.");
                } else {
                    sender.sendMessage("Could not send the resource pack. Check the configured URL.");
                }
                return true;
            case "off":
                rpm.disableFor(player);
                sender.sendMessage("Resource pack auto-send disabled.");
                return true;
            default:
                sender.sendMessage("Usage: /" + label + " <on|off>");
                return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> opts = Arrays.asList("on", "off");
            String prefix = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String o : opts) if (o.startsWith(prefix)) out.add(o);
            return out;
        }
        return Collections.emptyList();
    }
}

