package crabcraft.net.crabUtilities.velocity.messaging;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Optional;
import java.util.UUID;

public class ReplyCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("r")
                .then(BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            CommandSource source = ctx.getSource();
                            String message = ctx.getArgument("message", String.class);

                            UUID senderId = source instanceof Player p
                                    ? p.getUniqueId() : MessageManager.CONSOLE_UUID;
                            UUID partner = plugin.getMessageManager().getReplyTarget(senderId);
                            if (partner == null) {
                                source.sendMessage(MINI_MESSAGE.deserialize(
                                        plugin.getConfig().getMsgNoReplyTarget()));
                                return 0;
                            }

                            Optional<Player> target = plugin.getServer().getPlayer(partner);
                            if (target.isEmpty()) {
                                source.sendMessage(MINI_MESSAGE.deserialize(
                                        plugin.getConfig().getMsgPlayerNotFound()));
                                return 0;
                            }

                            plugin.getMessageManager().send(source, target.get(), message);
                            return 1;
                        })
                )
                .executes(ctx -> {
                    ctx.getSource().sendMessage(
                            Component.text("Usage: /r <message>", NamedTextColor.RED)
                    );
                    return 0;
                })
                .build();

        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .aliases("reply")
                        .plugin(plugin)
                        .build(),
                command
        );
    }
}
