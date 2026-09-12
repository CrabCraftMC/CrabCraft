package crabcraft.net.crabUtilities.velocity.messaging

import com.mojang.brigadier.tree.LiteralCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity

/** Restores messaging nodes after backend command trees have been merged. */
class MsgCommandTreeListener(plugin: CrabUtilitiesVelocity) {
    private val commands = MsgCommand.COMMAND_NAMES.map { name -> MsgCommand.buildNode(plugin, name) }
    @Subscribe(order = PostOrder.LAST)
    fun onPlayerAvailableCommands(event: PlayerAvailableCommandsEvent) { replaceCommands(commandRoot(event), commands) }
    companion object {
        @JvmStatic fun replaceCommands(root: RootCommandNode<CommandSource>, commands: Iterable<LiteralCommandNode<CommandSource>>) {
            for (command in commands) { root.removeChildByName(command.name); root.addChild(command) }
        }
        @Suppress("UNCHECKED_CAST")
        private fun commandRoot(event: PlayerAvailableCommandsEvent): RootCommandNode<CommandSource> = event.rootNode as RootCommandNode<CommandSource>
    }
}
