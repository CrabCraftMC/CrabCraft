package crabcraft.net.crabUtilities.velocity.staffchat;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;

public class StaffChatListener {

    private final CrabUtilitiesVelocity plugin;

    public StaffChatListener(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        StaffChatManager manager = plugin.getStaffChatManager();

        if (!manager.hasPermission(player)) return;
        if (!manager.isEnabled(player.getUniqueId())) return;

        String message = event.getMessage();
        if (!message.startsWith("#")) return;

        String staffMessage = message.substring(1);
        if (staffMessage.startsWith(" ")) {
            staffMessage = staffMessage.substring(1);
        }
        if (staffMessage.isEmpty()) return;

        event.setResult(PlayerChatEvent.ChatResult.denied());
        String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
        String senderName = raw != null ? raw : player.getUsername();
        manager.sendMessage(senderName, player.getUniqueId(), staffMessage);
    }
}
