package crabcraft.net.crabUtilities.velocity.staffchat;

import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.DiscordWebhook;
import crabcraft.net.crabUtilities.velocity.NicknameComponentParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaffChatManager {

    static final String PERMISSION = "crabutilities.staffchat";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final CrabUtilitiesVelocity plugin;
    private final RedisStaffChat redis;
    private final DiscordWebhook discordWebhook;
    private final String discordAvatarUrlTemplate;
    private final Set<UUID> disabledPlayers = ConcurrentHashMap.newKeySet();

    public StaffChatManager(CrabUtilitiesVelocity plugin, RedisStaffChat redis,
                            DiscordWebhook discordWebhook, String discordAvatarUrlTemplate) {
        this.plugin = plugin;
        this.redis = redis;
        this.discordWebhook = discordWebhook;
        this.discordAvatarUrlTemplate = discordAvatarUrlTemplate;
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

    public void sendMessage(String senderName, UUID senderUuid, Component message) {
        redis.publish(senderName, message);
        sendToDiscord(senderName, senderUuid, message);
    }

    private void sendToDiscord(String senderName, UUID senderUuid, Component message) {
        if (discordWebhook == null) return;
        String plainName = NicknameComponentParser.plain(senderName);
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(message);
        String avatarUrl = null;
        if (senderUuid != null && discordAvatarUrlTemplate != null && !discordAvatarUrlTemplate.isEmpty()) {
            avatarUrl = discordAvatarUrlTemplate.replace("{uuid}", senderUuid.toString());
        }
        discordWebhook.send(plainMessage, plainName, avatarUrl);
    }

    public void displayMessage(String senderName, Component message) {
        String format = plugin.getConfig().getStaffChatFormat();
        Component senderComponent = NicknameComponentParser.parse(senderName);
        Component component = MINI_MESSAGE.deserialize(format,
                Placeholder.component("sender", senderComponent),
                Placeholder.component("message", message)
        );

        for (Player player : plugin.getServer().getAllPlayers()) {
            if (player.hasPermission(PERMISSION) && isEnabled(player.getUniqueId())) {
                plugin.getMessageManager().deliver(player, component);
            }
        }

        String plainSender = PlainTextComponentSerializer.plainText().serialize(senderComponent);
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(message);
        plugin.getLogger().info("[StaffChat] {}: {}", plainSender, plainMessage);
    }
}
