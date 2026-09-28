package crabcraft.net.crabUtilities.velocity.messaging

import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

open class SocialSpyCommand {
    companion object {
        @JvmStatic
        fun register(plugin: CrabUtilitiesVelocity) {
            val node =
                BrigadierCommand.literalArgumentBuilder("socialspy")
                    .requires { it is Player && it.hasPermission(MessageManager.SOCIALSPY_PERMISSION) }
                    .executes { context ->
                        val player = context.source as Player
                        val nowEnabled = plugin.getMessageManager()!!.toggleSpy(player.uniqueId)
                        player.sendMessage(
                            if (nowEnabled) Component.text("Social spy enabled.", NamedTextColor.GREEN)
                            else Component.text("Social spy disabled.", NamedTextColor.RED)
                        )
                        1
                    }
                    .build()
            val command = BrigadierCommand(node)
            val manager = plugin.getServer().commandManager
            manager.register(manager.metaBuilder(command).aliases("sspy").plugin(plugin).build(), command)
        }
    }
}
