package crabcraft.net.crabUtilities.velocity.messaging

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import com.velocitypowered.api.command.CommandSource


object MsgCommandTreeRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = RootCommandNode<CommandSource>()
        val essentialsMsg = LiteralArgumentBuilder
                .literal<CommandSource>("msg")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .argument<CommandSource, String>(
                                "essentials-target", StringArgumentType.word()))
                .build()
        root.addChild(essentialsMsg)

        val crabMsg = command("msg")
        val crabMessage = command("message")
        MsgCommandTreeListener.replaceCommands(root, listOf(crabMsg, crabMessage))

        check(root.getChild("msg") === crabMsg,
                "the backend /msg node was not replaced by CrabUtilities")
        check(root.getChild("msg")!!.getChild("target") != null,
                "the CrabUtilities target argument was missing from /msg")
        check(root.getChild("message") === crabMessage,
                "the CrabUtilities /message node was not added")
    }

    private fun command(name: String): LiteralCommandNode<CommandSource> {
        return LiteralArgumentBuilder.literal<CommandSource>(name)
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .argument<CommandSource, String>(
                                "target", StringArgumentType.string()))
                .build()
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
