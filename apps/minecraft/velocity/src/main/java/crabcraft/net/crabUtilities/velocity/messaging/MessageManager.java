package crabcraft.net.crabUtilities.velocity.messaging;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.NicknameComponentParser;
import crabcraft.net.crabUtilities.velocity.PlayerSettingsService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MessageManager {

    public static final UUID CONSOLE_UUID = new UUID(0L, 0L);
    public static final String SOCIALSPY_PERMISSION = "crabutilities.socialspy";
    public static final String BYPASS_DND_PERMISSION = "crabutilities.msg.bypassdnd";
    private static final String CONSOLE_NAME = "Console";
    private static final String NOT_ACCEPTING_MESSAGE =
            "<#f77069>That player isn't accepting private messages right now.";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final CrabUtilitiesVelocity plugin;
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();
    private final Set<UUID> socialSpies = ConcurrentHashMap.newKeySet();

    public MessageManager(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    public void send(CommandSource source, Player target, String message) {
        UUID senderId = source instanceof Player p ? p.getUniqueId() : CONSOLE_UUID;
        if (senderId.equals(target.getUniqueId())) {
            source.sendMessage(MINI_MESSAGE.deserialize(plugin.getConfig().getMsgSelfError()));
            return;
        }

        // Respect the target's "accept private messages" setting, unless the
        // sender can bypass it (staff). Reads the proxy-side settings cache.
        PlayerSettingsService settingsService = plugin.getPlayerSettingsService();
        if (settingsService != null
                && !settingsService.acceptsMessages(target.getUniqueId())
                && !source.hasPermission(BYPASS_DND_PERMISSION)) {
            source.sendMessage(MINI_MESSAGE.deserialize(NOT_ACCEPTING_MESSAGE));
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
        playIncomingSound(target);

        replyTargets.put(senderId, target.getUniqueId());
        replyTargets.put(target.getUniqueId(), senderId);

        broadcastToSpies(senderComponent, targetComponent, message, senderId, target.getUniqueId());

        String plainSender = PlainTextComponentSerializer.plainText().serialize(senderComponent);
        String plainTarget = PlainTextComponentSerializer.plainText().serialize(targetComponent);
        plugin.getLogger().info("[MSG] {} -> {}: {}", plainSender, plainTarget, message);
    }

    private void playIncomingSound(Player target) {
        if (!plugin.getConfig().isMsgIncomingSoundEnabled()) return;
        Key soundKey = Key.key(plugin.getConfig().getMsgIncomingSoundKey());
        Sound sound = Sound.sound(
                soundKey,
                Sound.Source.MASTER,
                plugin.getConfig().getMsgIncomingSoundVolume(),
                plugin.getConfig().getMsgIncomingSoundPitch()
        );
        target.playSound(sound, Sound.Emitter.self());
    }

    private void broadcastToSpies(Component senderComponent, Component targetComponent,
                                  String message, UUID senderId, UUID targetId) {
        if (socialSpies.isEmpty()) return;

        Component spyMessage = MINI_MESSAGE.deserialize(plugin.getConfig().getMsgSpyFormat(),
                Placeholder.component("sender", senderComponent),
                Placeholder.component("target", targetComponent),
                Placeholder.unparsed("message", message)
        );

        for (UUID spyId : socialSpies) {
            if (spyId.equals(senderId) || spyId.equals(targetId)) continue;
            plugin.getServer().getPlayer(spyId).ifPresent(spy -> {
                if (spy.hasPermission(SOCIALSPY_PERMISSION)) {
                    spy.sendMessage(spyMessage);
                }
            });
        }
    }

    public boolean toggleSpy(UUID uuid) {
        if (socialSpies.remove(uuid)) {
            return false;
        }
        socialSpies.add(uuid);
        return true;
    }

    public void clearSpy(UUID uuid) {
        socialSpies.remove(uuid);
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
            ClickEvent click = ClickEvent.suggestCommand(messageCommand(player.getUsername()));
            if (raw != null) {
                return NicknameComponentParser.parse(raw).clickEvent(click);
            }
            return Component.text(player.getUsername()).clickEvent(click);
        }
        return Component.text(CONSOLE_NAME);
    }

    private static String messageCommand(String username) {
        return "/msg " + username + " ";
    }
}
