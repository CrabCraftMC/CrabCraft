package crabcraft.net.crabUtilities.velocity;

import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ReloadCommand {

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("crabutilitiesproxy")
                .requires(source -> source.hasPermission("crabutilities.reload")
                        || source.hasPermission("crabutilities.webapi"))
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
                .executes(ctx -> {
                    ctx.getSource().sendMessage(Component.text(
                            "Usage: /crabutilitiesproxy <reload|webapi>", NamedTextColor.RED));
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
}
