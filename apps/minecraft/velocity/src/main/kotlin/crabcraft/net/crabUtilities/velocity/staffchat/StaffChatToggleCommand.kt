package crabcraft.net.crabUtilities.velocity.staffchat

import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

open class StaffChatToggleCommand {
    companion object {
        @JvmStatic fun register(plugin: CrabUtilitiesVelocity) {
            val node = BrigadierCommand.literalArgumentBuilder("sctoggle")
                .requires { source -> source is Player && source.hasPermission(StaffChatManager.PERMISSION) }
                .executes { ctx ->
                    val player = ctx.source as Player
                    val nowEnabled = plugin.getStaffChatManager().toggle(player.uniqueId)
                    val result = if (nowEnabled) Component.text("Staff chat enabled.", NamedTextColor.GREEN)
                        else Component.text("Staff chat disabled.", NamedTextColor.RED)
                    plugin.getChatBridge()!!.syncStaffState(player, nowEnabled)
                    plugin.getMessageManager().deliver(player, result)
                    1
                }.build()
            val command = BrigadierCommand(node)
            plugin.getServer().commandManager.register(plugin.getServer().commandManager.metaBuilder(command).plugin(plugin).build(), command)
        }
    }
}
