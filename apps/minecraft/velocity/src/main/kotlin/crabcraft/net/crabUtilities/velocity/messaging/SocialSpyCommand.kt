package crabcraft.net.crabUtilities.velocity.messaging

import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

open class SocialSpyCommand {
    companion object {
        @JvmStatic fun register(plugin: CrabUtilitiesVelocity) {
            val node = BrigadierCommand.literalArgumentBuilder("socialspy")
                .requires { source -> source is Player && source.hasPermission(MessageManager.SOCIALSPY_PERMISSION) }
                .executes { ctx ->
                    val player = ctx.source as Player
                    val nowEnabled = plugin.getMessageManager().toggleSpy(player.uniqueId)
                    if (nowEnabled) player.sendMessage(Component.text("Social spy enabled.", NamedTextColor.GREEN))
                    else player.sendMessage(Component.text("Social spy disabled.", NamedTextColor.RED))
                    1
                }.build()
            val command = BrigadierCommand(node)
            plugin.getServer().commandManager.register(plugin.getServer().commandManager.metaBuilder(command).aliases("sspy").plugin(plugin).build(), command)
        }
    }
}
