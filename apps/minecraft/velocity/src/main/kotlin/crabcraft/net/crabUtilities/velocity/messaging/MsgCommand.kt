package crabcraft.net.crabUtilities.velocity.messaging

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage

object MsgCommand {
    private val MINI_MESSAGE = MiniMessage.miniMessage()
    @JvmField val COMMAND_NAMES = listOf("msg", "message", "tell", "whisper", "w", "dm")

    @JvmStatic
    fun register(plugin: CrabUtilitiesVelocity) {
        val command = BrigadierCommand(buildNode(plugin, "msg"))
        val manager = plugin.getServer().commandManager
        manager.register(
            manager.metaBuilder(command).aliases("message", "tell", "whisper", "w", "dm").plugin(plugin).build(),
            command,
        )
        plugin.getServer().eventManager.register(plugin, MsgCommandTreeListener(plugin))
    }

    @JvmStatic
    fun buildNode(plugin: CrabUtilitiesVelocity, commandName: String): LiteralCommandNode<CommandSource> =
        BrigadierCommand.literalArgumentBuilder(commandName)
            .then(
                BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.string())
                    .suggests(PlayerLookup.playerSuggestions(plugin))
                    .then(
                        BrigadierCommand.requiredArgumentBuilder("message", StringArgumentType.greedyString())
                            .executes { context ->
                                val source = context.source
                                val targetName = context.getArgument("target", String::class.java)
                                val message = context.getArgument("message", String::class.java)
                                val target = PlayerLookup.resolve(plugin, targetName)
                                if (target.isEmpty) {
                                    source.sendMessage(
                                        MINI_MESSAGE.deserialize(plugin.getConfig().getMsgPlayerNotFound())
                                    )
                                    0
                                } else {
                                    plugin.getMessageManager()!!.send(source, target.get(), Component.text(message))
                                    1
                                }
                            }
                    )
            )
            .executes { context ->
                context.source.sendMessage(
                    Component.text("Usage: /$commandName <player> <message>", NamedTextColor.RED)
                )
                0
            }
            .build()
}
