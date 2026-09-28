package crabcraft.net.crabUtilities.jade

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.jade.protocol.JadeProtocol
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.ServerLoadEvent

class JadeIntegration : Listener, CommandExecutor {
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        JadeProtocol.onPlayerLeave((event.player as CraftPlayer).handle)
    }

    @EventHandler
    fun onServerLoad(event: ServerLoadEvent) {
        if (event.type == ServerLoadEvent.LoadType.RELOAD) JadeProtocol.onServerReload()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(CrabMessages.error("Only players can use this command."))
            return true
        }
        JadeProtocol.resendHandshake((sender as CraftPlayer).handle)
        sender.sendMessage(CrabMessages.success("Jade handshake resent."))
        return true
    }
}
