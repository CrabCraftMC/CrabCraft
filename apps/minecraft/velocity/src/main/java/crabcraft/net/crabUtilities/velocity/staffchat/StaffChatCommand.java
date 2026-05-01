package crabcraft.net.crabUtilities.velocity.staffchat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class StaffChatCommand {

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("sc")
                .requires(source -> source.hasPermission("crabutilities.staffchat"))
                .then(BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            CommandSource source = ctx.getSource();
                            String message = ctx.getArgument("message", String.class);

                            String senderName;
                            if (source instanceof Player player) {
                                String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
                                senderName = raw != null ? raw : player.getUsername();
                            } else {
                                senderName = "Console";
                            }

                            plugin.getStaffChatManager().sendMessage(senderName, message);
                            return 1;
                        })
                )
                .executes(ctx -> {
                    ctx.getSource().sendMessage(
                            Component.text("Usage: /sc <message>", NamedTextColor.RED)
                    );
                    return 0;
                })
                .build();

        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .aliases("staffchat")
                        .plugin(plugin)
                        .build(),
                command
        );
    }
}
