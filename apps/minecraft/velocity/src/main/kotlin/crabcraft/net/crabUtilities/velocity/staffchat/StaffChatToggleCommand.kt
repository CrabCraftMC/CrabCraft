package crabcraft.net.crabUtilities.velocity.staffchat

import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

open class StaffChatToggleCommand {
    companion object {
        @JvmStatic
        fun register(plugin: CrabUtilitiesVelocity) {
            val node =
                BrigadierCommand.literalArgumentBuilder("sctoggle")
                    .requires { it is Player && it.hasPermission(StaffChatManager.PERMISSION) }
                    .executes { context ->
                        val player = context.source as Player
                        val nowEnabled = plugin.getStaffChatManager()!!.toggle(player.uniqueId)
                        val result =
                            if (nowEnabled) Component.text("Staff chat enabled.", NamedTextColor.GREEN)
                            else Component.text("Staff chat disabled.", NamedTextColor.RED)
                        plugin.getChatBridge()!!.syncStaffState(player, nowEnabled)
                        plugin.getMessageManager()!!.deliver(player, result)
                        1
                    }
                    .build()
            val command = BrigadierCommand(node)
            val manager = plugin.getServer().commandManager
            manager.register(manager.metaBuilder(command).plugin(plugin).build(), command)
        }
    }
}
