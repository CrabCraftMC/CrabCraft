package crabcraft.net.crabUtilities.velocity.voicechat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.messaging.PlayerLookup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Optional;

/** Registers the network-wide player voice-call command. */
public final class CallCommand {

    private CallCommand() {}

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("call")
                .then(BrigadierCommand.literalArgumentBuilder("accept")
                        .then(BrigadierCommand.requiredArgumentBuilder("token", StringArgumentType.word())
                                .executes(context -> {
                                    if (!(context.getSource() instanceof Player player)) {
                                        playerOnly(context.getSource());
                                        return 0;
                                    }
                                    CallManager manager = plugin.getCallManager();
                                    if (manager == null) {
                                        unavailable(player);
                                        return 0;
                                    }
                                    manager.accept(player, context.getArgument("token", String.class));
                                    return 1;
                                })))
                .then(BrigadierCommand.literalArgumentBuilder("decline")
                        .then(BrigadierCommand.requiredArgumentBuilder("token", StringArgumentType.word())
                                .executes(context -> {
                                    if (!(context.getSource() instanceof Player player)) {
                                        playerOnly(context.getSource());
                                        return 0;
                                    }
                                    CallManager manager = plugin.getCallManager();
                                    if (manager == null) {
                                        unavailable(player);
                                        return 0;
                                    }
                                    manager.decline(player, context.getArgument("token", String.class));
                                    return 1;
                                })))
                .then(BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.string())
                        .suggests(PlayerLookup.playerSuggestions(plugin))
                        .executes(context -> {
                            if (!(context.getSource() instanceof Player caller)) {
                                playerOnly(context.getSource());
                                return 0;
                            }
                            String targetName = context.getArgument("target", String.class);
                            Optional<Player> target = PlayerLookup.resolve(plugin, targetName);
                            if (target.isEmpty()) {
                                caller.sendMessage(Component.text(
                                        "Player not found or not online.", NamedTextColor.RED));
                                return 0;
                            }
                            CallManager manager = plugin.getCallManager();
                            if (manager == null) {
                                unavailable(caller);
                                return 0;
                            }
                            manager.invite(caller, target.get());
                            return 1;
                        }))
                .executes(context -> {
                    context.getSource().sendMessage(Component.text(
                            "Usage: /call <player>", NamedTextColor.RED));
                    return 0;
                })
                .build();

        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .plugin(plugin)
                        .build(),
                command);
    }

    private static void playerOnly(CommandSource source) {
        source.sendMessage(Component.text("Only players can use /call.", NamedTextColor.RED));
    }

    private static void unavailable(Player player) {
        player.sendMessage(Component.text("Voice calls are not available right now.", NamedTextColor.RED));
    }
}
