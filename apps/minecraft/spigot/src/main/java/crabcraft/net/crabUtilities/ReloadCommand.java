package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.config.ModuleConfigException;
import crabcraft.net.crabUtilities.update.UpdateCommand;
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class ReloadCommand implements CommandExecutor, TabCompleter {

    private final CrabUtilities plugin;
    private final UpdateCommand updateCommand;
    private final ViewDistanceCommand viewDistanceCommand;

    public ReloadCommand(
            CrabUtilities plugin,
            UpdateCommand updateCommand,
            ViewDistanceCommand viewDistanceCommand) {
        this.plugin = plugin;
        this.updateCommand = updateCommand;
        this.viewDistanceCommand = viewDistanceCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("crabutilities.reload")) {
                sender.sendMessage(CrabMessages.error(
                        "You don't have permission to reload CrabUtilities."));
                return true;
            }
            if (args.length > 2) {
                sendReloadUsage(sender);
                return true;
            }
            String target = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "all";
            if (!plugin.getConfigReloadTargets().contains(target)) {
                sendReloadUsage(sender);
                return true;
            }
            long startedAt = System.nanoTime();
            sender.sendMessage(CrabMessages.text(reloadingMessage(target)));
            List<String> messages;
            try {
                messages = plugin.reloadRuntimeConfig(target);
            } catch (ModuleConfigException e) {
                sender.sendMessage(CrabMessages.error(
                        "Reload failed (" + elapsedMillis(startedAt) + " ms): "
                                + e.getMessage()));
                return true;
            }
            for (String message : messages) {
                plugin.getLogger().info("Reload " + target + ": " + message);
            }
            sender.sendMessage(CrabMessages.success(
                    reloadedMessage(elapsedMillis(startedAt))));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("update") && updateCommand != null) {
            return updateCommand.handle(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("viewdistance")) {
            return viewDistanceCommand.handle(sender, args);
        }
        sender.sendMessage(CrabMessages.error(
                "Usage: /crabutilities <reload|update|viewdistance>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(Arrays.asList("reload", "update", "viewdistance"));
            String p = args[0].toLowerCase(Locale.ROOT);
            return opts.stream().filter(o -> o.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getConfigReloadTargets().stream()
                    .filter(target -> target.startsWith(prefix))
                    .toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("update") && updateCommand != null) {
            return updateCommand.tabComplete(args);
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("viewdistance")) {
            return viewDistanceCommand.tabComplete(sender, args);
        }
        return Collections.emptyList();
    }

    private void sendReloadUsage(CommandSender sender) {
        sender.sendMessage(CrabMessages.error(
                "Usage: /crabutilities reload ["
                        + String.join("|", plugin.getConfigReloadTargets())
                        + "]"));
    }

    static String reloadingMessage(String target) {
        return "Reloading " + target + "...";
    }

    static String reloadedMessage(long elapsedMillis) {
        return "Reloaded (" + elapsedMillis + " ms)";
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
