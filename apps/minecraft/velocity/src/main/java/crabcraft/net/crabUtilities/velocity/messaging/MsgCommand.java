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

import java.util.List;
import java.util.Optional;

public final class MsgCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    static final List<String> COMMAND_NAMES = List.of(
            "msg", "message", "tell", "whisper", "w", "dm");

    private MsgCommand() {}

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = buildNode(plugin, "msg");
        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .aliases("message", "tell", "whisper", "w", "dm")
                        .plugin(plugin)
                        .build(),
                command
        );
        plugin.getServer().getEventManager().register(
                plugin, new MsgCommandTreeListener(plugin));
    }

    static LiteralCommandNode<CommandSource> buildNode(
            CrabUtilitiesVelocity plugin, String commandName) {
        return BrigadierCommand.literalArgumentBuilder(commandName)
                .then(BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.string())
                        .suggests(PlayerLookup.playerSuggestions(plugin))
                        .then(BrigadierCommand.requiredArgumentBuilder(
                                        "message", StringArgumentType.greedyString())
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

                                    plugin.getMessageManager().send(
                                            source, target.get(), Component.text(message));
                                    return 1;
                                })
                        )
                )
                .executes(ctx -> {
                    ctx.getSource().sendMessage(Component.text(
                            "Usage: /" + commandName + " <player> <message>",
                            NamedTextColor.RED));
                    return 0;
                })
                .build();
    }
}
