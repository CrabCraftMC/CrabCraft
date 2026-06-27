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

public class MsgCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    public static void register(CrabUtilitiesVelocity plugin) {
        register(plugin, "msg", "message", "tell", "whisper", "w", "dm");
    }

    private static void register(CrabUtilitiesVelocity plugin, String primary, String... aliases) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder(primary)
                .then(BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.word())
                        .suggests(PlayerLookup.playerSuggestions(plugin))
                        .then(BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    CommandSource source = ctx.getSource();
                                    String targetName = ctx.getArgument("target", String.class);
                                    String message = ctx.getArgument("message", String.class);

                                    Optional<Player> target = PlayerLookup.resolve(plugin, targetName);
                                    if (target.isEmpty()) {
                                        source.sendMessage(MINI_MESSAGE.deserialize(
                                                plugin.getConfig().getMsgPlayerNotFound()));
                                        return 0;
                                    }

                                    plugin.getMessageManager().send(source, target.get(), message);
                                    return 1;
                                })
                        )
                )
                .executes(ctx -> {
                    ctx.getSource().sendMessage(
                            Component.text("Usage: /" + primary + " <player> <message>", NamedTextColor.RED)
                    );
                    return 0;
                })
                .build();

        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .aliases(aliases)
                        .plugin(plugin)
                        .build(),
                command
        );
    }
}
