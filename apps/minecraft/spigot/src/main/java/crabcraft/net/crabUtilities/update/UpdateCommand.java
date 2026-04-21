package crabcraft.net.crabUtilities.update;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UpdateCommand {

    private final CrabUtilities plugin;
    private final UpdateService service;

    public UpdateCommand(CrabUtilities plugin, UpdateService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("crabutilities.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to run update commands.");
            return true;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "check" -> runAsync(sender, false);
            case "download" -> runAsync(sender, true);
            case "status" -> sendStatus(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return filter(Arrays.asList("check", "download", "status"), args[1]);
        }
        return Collections.emptyList();
    }

    private void runAsync(CommandSender sender, boolean download) {
        sender.sendMessage(ChatColor.GRAY + "[CrabUtilities] "
                + (download ? "Checking and downloading..." : "Checking for updates..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                service.runCheck(download, msg ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage(ChatColor.GRAY + "[CrabUtilities] "
                                        + ChatColor.WHITE + msg))));
    }

    private void sendStatus(CommandSender sender) {
        UpdateService.State s = service.getState();
        Instant last = service.getLastCheck();
        ReleaseInfo seen = service.getLastSeen();
        String err = service.getLastError();

        sender.sendMessage(ChatColor.GOLD + "CrabUtilities update status");
        sender.sendMessage(ChatColor.GRAY + "Running: " + ChatColor.WHITE + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "State: " + ChatColor.WHITE + s.name().toLowerCase());
        sender.sendMessage(ChatColor.GRAY + "Last check: " + ChatColor.WHITE
                + (last == null ? "never" : prettyAgo(last)));
        if (seen != null) {
            sender.sendMessage(ChatColor.GRAY + "Latest seen: " + ChatColor.WHITE + seen.tag()
                    + (seen.prerelease() ? ChatColor.YELLOW + " (pre-release)" : ""));
        }
        if (err != null) {
            sender.sendMessage(ChatColor.GRAY + "Last error: " + ChatColor.RED + err);
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Usage: /crabutilities update <check|download|status>");
    }

    private static String prettyAgo(Instant when) {
        Duration d = Duration.between(when, Instant.now());
        long s = d.getSeconds();
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }

    private static List<String> filter(List<String> opts, String prefix) {
        String p = prefix.toLowerCase();
        return opts.stream().filter(o -> o.startsWith(p)).toList();
    }
}
