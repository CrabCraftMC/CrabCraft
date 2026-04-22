package crabcraft.net.crabUtilities;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import net.ess3.api.events.NickChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public class NicknameSync implements Listener, PluginMessageListener {

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

    /**
     * When a player changes their nick via /nick, push it to Velocity
     * so it's cached and persisted to DB immediately.
     */
    @EventHandler
    public void onNickChange(NickChangeEvent event) {
        Player player = event.getAffected().getBase();
        // Delay one tick so EssentialsX has finished updating internally
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            String newNick = event.getValue() != null ? event.getValue() : "";
            sendNickname(player, newNick);
        }, 1L);
    }

    /**
     * Incoming message from Velocity pushing the authoritative nickname.
     * If it differs from the local EssentialsX nick, update it.
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!channel.equals(CHANNEL)) return;

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            UUID uuid = UUID.fromString(in.readUTF());
            String authoritative = in.readUTF();

            Player target = plugin.getServer().getPlayer(uuid);
            if (target == null || !target.isOnline()) return;

            // Delay so EssentialsX has loaded the user on this server
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!(plugin.getEssentials() instanceof Essentials essentials)) return;
                User user = essentials.getUser(target);
                if (user == null) return;

                String localNick = user.getNickname();
                if (localNick == null) localNick = "";

                // Only update if different and authoritative nick is not empty
                if (!authoritative.isEmpty() && !authoritative.equals(localNick)) {
                    user.setNickname(authoritative);
                    plugin.getLogger().info("Synced nickname for " + target.getName() + ": " + authoritative);
                } else if (authoritative.isEmpty() && !localNick.isEmpty()) {
                    user.setNickname(null);
                    plugin.getLogger().info("Cleared nickname for " + target.getName());
                }
            }, 20L);
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warning("Failed to parse incoming nickname message: " + e.getMessage());
        }
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
