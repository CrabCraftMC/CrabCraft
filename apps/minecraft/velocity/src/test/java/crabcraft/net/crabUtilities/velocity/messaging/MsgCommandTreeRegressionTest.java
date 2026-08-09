package crabcraft.net.crabUtilities.velocity.messaging;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;

import java.util.List;

final class MsgCommandTreeRegressionTest {

    public static void main(String[] args) {
        RootCommandNode<CommandSource> root = new RootCommandNode<>();
        LiteralCommandNode<CommandSource> essentialsMsg = LiteralArgumentBuilder
                .<CommandSource>literal("msg")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSource, String>argument(
                                "essentials-target", StringArgumentType.word()))
                .build();
        root.addChild(essentialsMsg);

        LiteralCommandNode<CommandSource> crabMsg = command("msg");
        LiteralCommandNode<CommandSource> crabMessage = command("message");
        MsgCommandTreeListener.replaceCommands(root, List.of(crabMsg, crabMessage));

        check(root.getChild("msg") == crabMsg,
                "the backend /msg node was not replaced by CrabUtilities");
        check(root.getChild("msg").getChild("target") != null,
                "the CrabUtilities target argument was missing from /msg");
        check(root.getChild("message") == crabMessage,
                "the CrabUtilities /message node was not added");
    }

    private static LiteralCommandNode<CommandSource> command(String name) {
        return LiteralArgumentBuilder.<CommandSource>literal(name)
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .<CommandSource, String>argument(
                                "target", StringArgumentType.string()))
                .build();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
