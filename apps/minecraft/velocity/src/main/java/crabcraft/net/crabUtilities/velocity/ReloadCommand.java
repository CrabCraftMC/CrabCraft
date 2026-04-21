package crabcraft.net.crabUtilities.velocity;

import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import crabcraft.net.crabUtilities.velocity.update.ReleaseInfo;
import crabcraft.net.crabUtilities.velocity.update.UpdateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.time.Instant;

public class ReloadCommand {

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("crabutilitiesproxy")
                .requires(source -> source.hasPermission("crabutilities.reload")
                        || source.hasPermission("crabutilities.webapi")
                        || source.hasPermission("crabutilities.update"))
                .then(BrigadierCommand.literalArgumentBuilder("reload")
                        .requires(source -> source.hasPermission("crabutilities.reload"))
                        .executes(ctx -> {
                            plugin.reload();
                            ctx.getSource().sendMessage(Component.text(
                                    "CrabUtilities Velocity config reloaded.", NamedTextColor.GREEN));
                            return 1;
                        })
                )
                .then(BrigadierCommand.literalArgumentBuilder("webapi")
                        .requires(source -> source.hasPermission("crabutilities.webapi"))
                        .then(BrigadierCommand.literalArgumentBuilder("start")
                                .executes(ctx -> {
                                    if (plugin.getWebServer().start()) {
                                        ctx.getSource().sendMessage(Component.text(
                                                "Web API started.", NamedTextColor.GREEN));
                                    } else {
                                        ctx.getSource().sendMessage(Component.text(
                                                "Web API is already running or failed to start. Check console for details.", NamedTextColor.RED));
                                    }
                                    return 1;
                                })
                        )
                        .then(BrigadierCommand.literalArgumentBuilder("stop")
                                .executes(ctx -> {
                                    if (plugin.getWebServer().isRunning()) {
                                        plugin.getWebServer().stop();
                                        ctx.getSource().sendMessage(Component.text(
                                                "Web API stopped.", NamedTextColor.GREEN));
                                    } else {
                                        ctx.getSource().sendMessage(Component.text(
                                                "Web API is not running.", NamedTextColor.RED));
                                    }
                                    return 1;
                                })
                        )
                        .executes(ctx -> {
                            ctx.getSource().sendMessage(Component.text(
                                    "Usage: /crabutilitiesproxy webapi <start|stop>", NamedTextColor.RED));
                            return 0;
                        })
                )
                .then(BrigadierCommand.literalArgumentBuilder("update")
                        .requires(source -> source.hasPermission("crabutilities.update"))
                        .then(BrigadierCommand.literalArgumentBuilder("check")
                                .executes(ctx -> runCheck(plugin, ctx.getSource(), false)))
                        .then(BrigadierCommand.literalArgumentBuilder("download")
                                .executes(ctx -> runCheck(plugin, ctx.getSource(), true)))
                        .then(BrigadierCommand.literalArgumentBuilder("status")
                                .executes(ctx -> {
                                    sendStatus(plugin, ctx.getSource());
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ctx.getSource().sendMessage(Component.text(
                                    "Usage: /crabutilitiesproxy update <check|download|status>",
                                    NamedTextColor.RED));
                            return 0;
                        })
                )
                .executes(ctx -> {
                    ctx.getSource().sendMessage(Component.text(
                            "Usage: /crabutilitiesproxy <reload|webapi|update>", NamedTextColor.RED));
                    return 0;
                })
                .build();

        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .plugin(plugin)
                        .build(),
                command
        );
    }

    private static int runCheck(CrabUtilitiesVelocity plugin, CommandSource source, boolean download) {
        UpdateService svc = plugin.getUpdateService();
        if (svc == null) {
            source.sendMessage(Component.text("Update service is disabled.", NamedTextColor.RED));
            return 0;
        }
        source.sendMessage(Component.text(
                download ? "Checking and downloading..." : "Checking for updates...",
                NamedTextColor.GRAY));
        plugin.getServer().getScheduler().buildTask(plugin, () ->
                svc.runCheck(download, source::sendMessage)
        ).schedule();
        return 1;
    }

    private static void sendStatus(CrabUtilitiesVelocity plugin, CommandSource source) {
        UpdateService svc = plugin.getUpdateService();
        if (svc == null) {
            source.sendMessage(Component.text("Update service is disabled.", NamedTextColor.RED));
            return;
        }
        UpdateService.State s = svc.getState();
        Instant last = svc.getLastCheck();
        ReleaseInfo seen = svc.getLastSeen();
        String err = svc.getLastError();

        source.sendMessage(Component.text("CrabUtilities (Velocity) update status", NamedTextColor.GOLD));
        source.sendMessage(Component.text("Running: " + BuildInfo.VERSION, NamedTextColor.GRAY));
        source.sendMessage(Component.text("State: " + s.name().toLowerCase(), NamedTextColor.GRAY));
        source.sendMessage(Component.text(
                "Last check: " + (last == null ? "never" : prettyAgo(last)), NamedTextColor.GRAY));
        if (seen != null) {
            source.sendMessage(Component.text("Latest seen: " + seen.tag()
                    + (seen.prerelease() ? " (pre-release)" : ""), NamedTextColor.GRAY));
        }
        if (err != null) {
            source.sendMessage(Component.text("Last error: " + err, NamedTextColor.RED));
        }
    }

    private static String prettyAgo(Instant when) {
        Duration d = Duration.between(when, Instant.now());
        long s = d.getSeconds();
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }
}
