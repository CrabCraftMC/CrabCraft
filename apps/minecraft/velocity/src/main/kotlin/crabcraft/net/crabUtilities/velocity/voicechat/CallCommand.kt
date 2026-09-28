package crabcraft.net.crabUtilities.velocity.voicechat

import com.mojang.brigadier.arguments.StringArgumentType
import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.messaging.PlayerLookup
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** Registers the network-wide player voice-call command. */
object CallCommand {
    @JvmStatic
    fun register(plugin: CrabUtilitiesVelocity) {
        val root = BrigadierCommand.literalArgumentBuilder("call")
        for (action in listOf("accept", "decline")) {
            root.then(
                BrigadierCommand.literalArgumentBuilder(action)
                    .then(
                        BrigadierCommand.requiredArgumentBuilder("token", StringArgumentType.word()).executes { context
                            ->
                            val player = context.source as? Player
                            if (player == null) {
                                playerOnly(context.source)
                                return@executes 0
                            }
                            val manager = plugin.getCallManager()
                            if (manager == null) {
                                unavailable(player)
                                return@executes 0
                            }
                            val token = context.getArgument("token", String::class.java)
                            if (action == "accept") manager.accept(player, token) else manager.decline(player, token)
                            1
                        }
                    )
            )
        }
        val node =
            root
                .then(
                    BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.string())
                        .suggests(PlayerLookup.playerSuggestions(plugin))
                        .executes { context ->
                            val caller = context.source as? Player
                            if (caller == null) {
                                playerOnly(context.source)
                                return@executes 0
                            }
                            val target = PlayerLookup.resolve(plugin, context.getArgument("target", String::class.java))
                            if (target.isEmpty) {
                                caller.sendMessage(
                                    Component.text("Player not found or not online.", NamedTextColor.RED)
                                )
                                return@executes 0
                            }
                            val manager = plugin.getCallManager()
                            if (manager == null) {
                                unavailable(caller)
                                return@executes 0
                            }
                            manager.invite(caller, target.get())
                            1
                        }
                )
                .executes { context ->
                    context.source.sendMessage(Component.text("Usage: /call <player>", NamedTextColor.RED))
                    0
                }
                .build()
        val command = BrigadierCommand(node)
        val manager = plugin.getServer().commandManager
        manager.register(manager.metaBuilder(command).plugin(plugin).build(), command)
    }

    private fun playerOnly(source: CommandSource) {
        source.sendMessage(Component.text("Only players can use /call.", NamedTextColor.RED))
    }

    private fun unavailable(player: Player) {
        player.sendMessage(Component.text("Voice calls are not available right now.", NamedTextColor.RED))
    }
}
