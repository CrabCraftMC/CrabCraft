package crabcraft.net.crabUtilities.velocity.staffchat;

import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaffChatManager {

    private static final String PERMISSION = "crabutilities.staffchat";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final CrabUtilitiesVelocity plugin;
    private final RedisStaffChat redis;
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();

    public StaffChatManager(CrabUtilitiesVelocity plugin, RedisStaffChat redis) {
        this.plugin = plugin;
        this.redis = redis;
    }

    public boolean hasPermission(Player player) {
        return player.hasPermission(PERMISSION);
    }

    public boolean isEnabled(UUID uuid) {
        return !disabledPlayers.contains(uuid);
    }

    public boolean toggle(UUID uuid) {
        if (disabledPlayers.remove(uuid)) {
            return true;
        } else {
            disabledPlayers.add(uuid);
            return false;
        }
    }

    public void sendMessage(String senderName, String message) {
        redis.publish(senderName, message);
    }

    public void displayMessage(String senderName, String message) {
        String format = plugin.getRedisStaffChat().getConfig().getStaffChatFormat();
        Component component = MINI_MESSAGE.deserialize(format,
                Placeholder.unparsed("sender", senderName),
                Placeholder.unparsed("message", message)
        );

        for (Player player : plugin.getServer().getAllPlayers()) {
            if (player.hasPermission(PERMISSION) && isEnabled(player.getUniqueId())) {
                player.sendMessage(component);
            }
        }

        plugin.getLogger().info("[StaffChat] {}: {}", senderName, message);
    }

    public String getPermission() {
        return PERMISSION;
    }
}
