package crabcraft.net.crabUtilities;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class NicknameSync implements Listener {

    private static final String CHANNEL = "crabutilities:nicknames";

    private final CrabUtilities plugin;

    public NicknameSync(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public void syncAll() {
        // Delay so the plugin channel is fully registered on both sides
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                String nickname = getNickname(online);
                sendNickname(online, nickname);
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Delay so EssentialsX has loaded the user and the plugin channel is ready
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            String nickname = getNickname(player);
            sendNickname(player, nickname);
        }, 20L);
    }

    private String getNickname(Player player) {
        if (!(plugin.getEssentials() instanceof Essentials essentials)) {
            return "";
        }
        User user = essentials.getUser(player);
        if (user == null) return "";
        String nick = user.getNickname();
        return nick != null ? nick : "";
    }

    private void sendNickname(Player player, String nickname) {
        if (!player.isOnline()) return;

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(player.getUniqueId().toString());
            out.writeUTF(nickname);
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send nickname for " + player.getName());
        }
    }
}
