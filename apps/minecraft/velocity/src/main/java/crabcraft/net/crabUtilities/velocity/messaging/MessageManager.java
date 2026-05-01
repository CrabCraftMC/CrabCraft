package crabcraft.net.crabUtilities.velocity.messaging;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MessageManager {

    public static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    private static final String CONSOLE_NAME = "Console";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexCharacter('#')
            .hexColors()
            .build();

    private final CrabUtilitiesVelocity plugin;
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();

    public MessageManager(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    public void send(CommandSource source, Player target, String message) {
        UUID senderId = source instanceof Player p ? p.getUniqueId() : CONSOLE_UUID;
        if (senderId.equals(target.getUniqueId())) {
            source.sendMessage(MINI_MESSAGE.deserialize(plugin.getConfig().getMsgSelfError()));
            return;
        }

        Component senderComponent = nameComponentFor(source);
        Component targetComponent = nameComponentFor(target);

        Component outgoing = MINI_MESSAGE.deserialize(plugin.getConfig().getMsgOutgoingFormat(),
                Placeholder.component("target", targetComponent),
                Placeholder.unparsed("message", message)
        );
        Component incoming = MINI_MESSAGE.deserialize(plugin.getConfig().getMsgIncomingFormat(),
                Placeholder.component("sender", senderComponent),
                Placeholder.unparsed("message", message)
        );

        source.sendMessage(outgoing);
        target.sendMessage(incoming);

        replyTargets.put(senderId, target.getUniqueId());
        replyTargets.put(target.getUniqueId(), senderId);

        String plainSender = PlainTextComponentSerializer.plainText().serialize(senderComponent);
        String plainTarget = PlainTextComponentSerializer.plainText().serialize(targetComponent);
        plugin.getLogger().info("[MSG] {} -> {}: {}", plainSender, plainTarget, message);
    }

    public UUID getReplyTarget(UUID uuid) {
        return replyTargets.get(uuid);
    }

    public void clearReplyTargets(UUID uuid) {
        UUID partner = replyTargets.remove(uuid);
        if (partner != null) {
            replyTargets.remove(partner, uuid);
        }
    }

    private Component nameComponentFor(CommandSource source) {
        if (source instanceof Player player) {
            String raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId());
            if (raw != null) {
                return LEGACY_SERIALIZER.deserialize(raw.replace('&', '§'));
            }
            return Component.text(player.getUsername());
        }
        return Component.text(CONSOLE_NAME);
    }
}
