package crabcraft.net.crabUtilities.velocity.messaging;

import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;

import java.util.List;

/**
 * Restores CrabUtilities messaging nodes after backend command trees have been merged.
 */
final class MsgCommandTreeListener {

    private final List<LiteralCommandNode<CommandSource>> commands;

    MsgCommandTreeListener(CrabUtilitiesVelocity plugin) {
        this.commands = MsgCommand.COMMAND_NAMES.stream()
                .map(name -> MsgCommand.buildNode(plugin, name))
                .toList();
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPlayerAvailableCommands(PlayerAvailableCommandsEvent event) {
        replaceCommands(commandRoot(event), commands);
    }

    static void replaceCommands(
            RootCommandNode<CommandSource> root,
            Iterable<? extends LiteralCommandNode<CommandSource>> commands) {
        for (LiteralCommandNode<CommandSource> command : commands) {
            root.removeChildByName(command.getName());
            root.addChild(command);
        }
    }

    @SuppressWarnings("unchecked")
    private static RootCommandNode<CommandSource> commandRoot(
            PlayerAvailableCommandsEvent event) {
        return (RootCommandNode<CommandSource>) event.getRootNode();
    }
}
