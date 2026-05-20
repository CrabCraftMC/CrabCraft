package crabcraft.net.crabUtilities.jade;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.protocol.jade.JadeProtocol;

public final class JadeIntegration implements Listener, CommandExecutor {

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        JadeProtocol.onPlayerLeave(((CraftPlayer) event.getPlayer()).getHandle());
    }

    @EventHandler
    public void onServerLoad(@NotNull ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.RELOAD) {
            JadeProtocol.onServerReload();
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only.");
            return true;
        }
        JadeProtocol.resendHandshake(((CraftPlayer) player).getHandle());
        player.sendMessage("[Jade] Resent handshake.");
        return true;
    }
}
