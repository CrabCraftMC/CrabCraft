package crabcraft.net.crabUtilities.update;

import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
            sender.sendMessage(CrabMessages.error(
                    "You don't have permission to run update commands."));
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
        sender.sendMessage(CrabMessages.text(
                download ? "Checking and downloading..." : "Checking for updates..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                service.runCheck(download, msg ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage(reportMessage(msg)))));
    }

    private void sendStatus(CommandSender sender) {
        UpdateService.State s = service.getState();
        Instant last = service.getLastCheck();
        ReleaseInfo seen = service.getLastSeen();
        String err = service.getLastError();

        sender.sendMessage(CrabMessages.accent("CrabUtilities update status"));
        sender.sendMessage(CrabMessages.label(
                "Running", plugin.getDescription().getVersion()));
        sender.sendMessage(CrabMessages.label("State", stateComponent(s)));
        sender.sendMessage(CrabMessages.label(
                "Last check", last == null ? "never" : prettyAgo(last)));
        if (seen != null) {
            Component latest = CrabMessages.text(seen.tag());
            if (seen.prerelease()) {
                latest = latest.append(CrabMessages.warning(" (pre-release)"));
            }
            sender.sendMessage(CrabMessages.label("Latest seen", latest));
        }
        if (err != null) {
            sender.sendMessage(CrabMessages.label(
                    "Last error", CrabMessages.error(err)));
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(CrabMessages.error(
                "Usage: /crabutilities update <check|download|status>"));
    }

    private Component reportMessage(String message) {
        return switch (service.getState()) {
            case ERROR -> CrabMessages.error(message);
            case READY, UP_TO_DATE -> CrabMessages.success(message);
            case CHECKING, DOWNLOADING -> CrabMessages.warning(message);
            case IDLE -> CrabMessages.text(message);
        };
    }

    private static Component stateComponent(UpdateService.State state) {
        String label = state.name().toLowerCase();
        return switch (state) {
            case ERROR -> CrabMessages.error(label);
            case READY, UP_TO_DATE -> CrabMessages.success(label);
            case CHECKING, DOWNLOADING -> CrabMessages.warning(label);
            case IDLE -> CrabMessages.text(label);
        };
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
