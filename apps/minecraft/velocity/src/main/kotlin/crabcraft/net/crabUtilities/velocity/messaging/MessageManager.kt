package crabcraft.net.crabUtilities.velocity.messaging

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.NicknameComponentParser
import crabcraft.net.crabUtilities.velocity.PlayerSettingsService
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

open class MessageManager(private val plugin: CrabUtilitiesVelocity) {
    private val replyTargets = ConcurrentHashMap<UUID, UUID>()
    private val socialSpies = ConcurrentHashMap.newKeySet<UUID>()
    companion object {
        @JvmField val CONSOLE_UUID = UUID(0L, 0L)
        const val SOCIALSPY_PERMISSION = "crabutilities.socialspy"
        const val BYPASS_DND_PERMISSION = "crabutilities.msg.bypassdnd"
        private const val CONSOLE_NAME = "Console"
        private const val NOT_ACCEPTING_MESSAGE = "<#f77069>That player isn't accepting private messages right now."
        private val MINI_MESSAGE = MiniMessage.miniMessage()
    }
    open fun sendToName(source: Player, targetName: String, message: Component) {
        val target = PlayerLookup.resolve(plugin, targetName)
        if (target.isEmpty()) {
            deliver(source, MINI_MESSAGE.deserialize(plugin.getConfig().getMsgPlayerNotFound()))
            return
        }
        send(source, target.get(), message)
    }

    open fun reply(source: Player, message: Component) {
        val partner = replyTargets.get(source.getUniqueId())
        if (partner == null) {
            deliver(source, MINI_MESSAGE.deserialize(plugin.getConfig().getMsgNoReplyTarget()))
            return
        }

        val target = plugin.getServer().getPlayer(partner)
        if (target.isEmpty()) {
            deliver(source, MINI_MESSAGE.deserialize(plugin.getConfig().getMsgPlayerNotFound()))
            return
        }
        send(source, target.get(), message)
    }

    open fun send(source: CommandSource, target: Player, message: Component) {
        val senderId = if (source is Player) source.uniqueId else CONSOLE_UUID
        if (senderId.equals(target.getUniqueId())) {
            deliver(source, MINI_MESSAGE.deserialize(plugin.getConfig().getMsgSelfError()))
            return
        }

        // Respect the target's "accept private messages" setting, unless the
        // sender can bypass it (staff). Reads the proxy-side settings cache.
        val settingsService = plugin.getPlayerSettingsService()
        if (settingsService != null
                && !settingsService.acceptsMessages(target.getUniqueId())
                && !source.hasPermission(BYPASS_DND_PERMISSION)) {
            deliver(source, MINI_MESSAGE.deserialize(NOT_ACCEPTING_MESSAGE))
            return
        }

        val senderComponent = nameComponentFor(source)
        val targetComponent = nameComponentFor(target)

        val outgoing = MINI_MESSAGE.deserialize(plugin.getConfig().getMsgOutgoingFormat(),
                Placeholder.component("target", targetComponent),
                Placeholder.component("message", message)
        )
        val incoming = MINI_MESSAGE.deserialize(plugin.getConfig().getMsgIncomingFormat(),
                Placeholder.component("sender", senderComponent),
                Placeholder.component("message", message)
        )

        deliver(source, outgoing)
        deliver(target, incoming)
        playIncomingSound(target)

        replyTargets.put(senderId, target.getUniqueId())
        replyTargets.put(target.getUniqueId(), senderId)

        broadcastToSpies(senderComponent, targetComponent, message, senderId, target.getUniqueId())

        val plainSender = PlainTextComponentSerializer.plainText().serialize(senderComponent)
        val plainTarget = PlainTextComponentSerializer.plainText().serialize(targetComponent)
        val plainMessage = PlainTextComponentSerializer.plainText().serialize(message)
        plugin.getLogger().info("[MSG] {} -> {}: {}", plainSender, plainTarget, plainMessage)
    }

    private fun playIncomingSound(target: Player) {
        if (!plugin.getConfig().isMsgIncomingSoundEnabled()) return
        val soundKey = Key.key(plugin.getConfig().getMsgIncomingSoundKey())
        val sound = Sound.sound(
                soundKey,
                Sound.Source.MASTER,
                plugin.getConfig().getMsgIncomingSoundVolume(),
                plugin.getConfig().getMsgIncomingSoundPitch()
        )
        target.playSound(sound, Sound.Emitter.self())
    }

    private fun broadcastToSpies(senderComponent: Component, targetComponent: Component,
                                  message: Component, senderId: UUID, targetId: UUID) {
        if (socialSpies.isEmpty()) return

        val spyMessage = MINI_MESSAGE.deserialize(plugin.getConfig().getMsgSpyFormat(),
                Placeholder.component("sender", senderComponent),
                Placeholder.component("target", targetComponent),
                Placeholder.component("message", message)
        )

        for (spyId in socialSpies) {
            if (spyId.equals(senderId) || spyId.equals(targetId)) continue
            plugin.getServer().getPlayer(spyId).ifPresent { spy ->
                if (spy.hasPermission(SOCIALSPY_PERMISSION)) {
                    deliver(spy, spyMessage)
                }
            }
        }
    }

    open fun deliver(player: Player, component: Component) {
        val bridge = plugin.getChatBridge()
        if (bridge == null || !bridge.deliver(player, component)) {
            player.sendMessage(component)
        }
    }

    private fun deliver(source: CommandSource, component: Component) {
        if (source is Player) {
            val player = source
            deliver(player, component)
        } else {
            source.sendMessage(component)
        }
    }

    open fun toggleSpy(uuid: UUID): Boolean {
        if (socialSpies.remove(uuid)) {
            return false
        }
        socialSpies.add(uuid)
        return true
    }

    open fun clearSpy(uuid: UUID) {
        socialSpies.remove(uuid)
    }

    open fun clearReplyTargets(uuid: UUID) {
        val partner = replyTargets.remove(uuid)
        if (partner != null) {
            replyTargets.remove(partner, uuid)
        }
    }

    private fun nameComponentFor(source: CommandSource): Component {
        if (source is Player) {
            val player = source
            val raw = plugin.getNicknameCache().getRawNickname(player.getUniqueId())
            val click = ClickEvent.suggestCommand(messageCommand(player.getUsername()))
            if (raw != null) {
                return NicknameComponentParser.parse(raw).clickEvent(click)
            }
            return Component.text(player.getUsername()).clickEvent(click)
        }
        return Component.text(CONSOLE_NAME)
    }

    private fun messageCommand(username: String): String {
        return "/msg " + username + " "
    }
}
