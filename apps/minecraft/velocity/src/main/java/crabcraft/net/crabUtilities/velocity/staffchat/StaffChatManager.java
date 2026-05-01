package crabcraft.net.crabUtilities.velocity.staffchat;

import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaffChatManager {

    private static final String PERMISSION = "crabutilities.staffchat";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexCharacter('#')
            .hexColors()
            .build();

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
        Component senderComponent = LEGACY_SERIALIZER.deserialize(senderName.replace('&', '§'));
        Component component = MINI_MESSAGE.deserialize(format,
                Placeholder.component("sender", senderComponent),
                Placeholder.unparsed("message", message)
        );

        for (Player player : plugin.getServer().getAllPlayers()) {
            if (player.hasPermission(PERMISSION) && isEnabled(player.getUniqueId())) {
                player.sendMessage(component);
            }
        }

        String plainSender = PlainTextComponentSerializer.plainText().serialize(senderComponent);
        plugin.getLogger().info("[StaffChat] {}: {}", plainSender, message);
    }

    public String getPermission() {
        return PERMISSION;
    }
}
