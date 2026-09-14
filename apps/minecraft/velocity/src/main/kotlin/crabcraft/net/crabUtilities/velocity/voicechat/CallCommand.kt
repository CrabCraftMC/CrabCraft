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
class CallCommand private constructor() {
    companion object {
        @JvmStatic fun register(plugin: CrabUtilitiesVelocity) {
            val node = BrigadierCommand.literalArgumentBuilder("call")
                .then(BrigadierCommand.literalArgumentBuilder("accept")
                    .then(BrigadierCommand.requiredArgumentBuilder("token", StringArgumentType.word())
                        .executes { context ->
                            val player = context.source as? Player
                            if (player == null) { playerOnly(context.source); return@executes 0 }
                            val manager = plugin.getCallManager()
                            if (manager == null) { unavailable(player); return@executes 0 }
                            manager.accept(player, context.getArgument("token", String::class.java))
                            1
                        }))
                .then(BrigadierCommand.literalArgumentBuilder("decline")
                    .then(BrigadierCommand.requiredArgumentBuilder("token", StringArgumentType.word())
                        .executes { context ->
                            val player = context.source as? Player
                            if (player == null) { playerOnly(context.source); return@executes 0 }
                            val manager = plugin.getCallManager()
                            if (manager == null) { unavailable(player); return@executes 0 }
                            manager.decline(player, context.getArgument("token", String::class.java))
                            1
                        }))
                .then(BrigadierCommand.requiredArgumentBuilder("target", StringArgumentType.string())
                    .suggests(PlayerLookup.playerSuggestions(plugin))
                    .executes { context ->
                        val caller = context.source as? Player
                        if (caller == null) { playerOnly(context.source); return@executes 0 }
                        val target = PlayerLookup.resolve(plugin, context.getArgument("target", String::class.java))
                        if (target.isEmpty) {
                            caller.sendMessage(Component.text("Player not found or not online.", NamedTextColor.RED))
                            return@executes 0
                        }
                        val manager = plugin.getCallManager()
                        if (manager == null) { unavailable(caller); return@executes 0 }
                        manager.invite(caller, target.get())
                        1
                    })
                .executes { context ->
                    context.source.sendMessage(Component.text("Usage: /call <player>", NamedTextColor.RED))
                    0
                }.build()
            val command = BrigadierCommand(node)
            plugin.getServer().commandManager.register(plugin.getServer().commandManager.metaBuilder(command).plugin(plugin).build(), command)
        }
        private fun playerOnly(source: CommandSource) { source.sendMessage(Component.text("Only players can use /call.", NamedTextColor.RED)) }
        private fun unavailable(player: Player) { player.sendMessage(Component.text("Voice calls are not available right now.", NamedTextColor.RED)) }
    }
}
