package crabcraft.net.crabUtilities.velocity.messaging

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.NicknameComponentParser
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

open class MessageManager(private val plugin: CrabUtilitiesVelocity) {
    private val replyTargets = ConcurrentHashMap<UUID, UUID>()
    private val socialSpies = ConcurrentHashMap.newKeySet<UUID>()

    open fun sendToName(source: Player, targetName: String, message: Component) {
        val target = PlayerLookup.resolve(plugin, targetName)
        if (target.isEmpty) {
            deliver(source, MINI_MESSAGE.deserialize(plugin.getConfig().getMsgPlayerNotFound()))
            return
        }
        send(source, target.get(), message)
    }

    open fun reply(source: Player, message: Component) {
        val partner = replyTargets[source.uniqueId]
        if (partner == null) {
            deliver(source, MINI_MESSAGE.deserialize(plugin.getConfig().getMsgNoReplyTarget()))
            return
        }
        val target = plugin.getServer().getPlayer(partner).filter(plugin.getVanishManager()::isVisible)
        if (target.isEmpty) {
            deliver(source, MINI_MESSAGE.deserialize(plugin.getConfig().getMsgPlayerNotFound()))
            return
        }
        send(source, target.get(), message)
    }

    open fun send(source: CommandSource, target: Player, message: Component) {
        val senderId = (source as? Player)?.uniqueId ?: CONSOLE_UUID
        if (senderId == target.uniqueId) {
            deliver(source, MINI_MESSAGE.deserialize(plugin.getConfig().getMsgSelfError()))
            return
        }
        val settings = plugin.getPlayerSettingsService()
        if (
            settings != null &&
                !settings.acceptsMessages(target.uniqueId) &&
                !source.hasPermission(BYPASS_DND_PERMISSION)
        ) {
            deliver(source, MINI_MESSAGE.deserialize(NOT_ACCEPTING_MESSAGE))
            return
        }
        val senderComponent = nameComponentFor(source)
        val targetComponent = nameComponentFor(target)
        val outgoing =
            MINI_MESSAGE.deserialize(
                plugin.getConfig().getMsgOutgoingFormat(),
                Placeholder.component("target", targetComponent),
                Placeholder.component("message", message),
            )
        val incoming =
            MINI_MESSAGE.deserialize(
                plugin.getConfig().getMsgIncomingFormat(),
                Placeholder.component("sender", senderComponent),
                Placeholder.component("message", message),
            )
        deliver(source, outgoing)
        deliver(target, incoming)
        playIncomingSound(target)
        replyTargets[senderId] = target.uniqueId
        replyTargets[target.uniqueId] = senderId
        broadcastToSpies(senderComponent, targetComponent, message, senderId, target.uniqueId)
        val plain = PlainTextComponentSerializer.plainText()
        plugin
            .getLogger()
            .info(
                "[MSG] {} -> {}: {}",
                plain.serialize(senderComponent),
                plain.serialize(targetComponent),
                plain.serialize(message),
            )
    }

    private fun playIncomingSound(target: Player) {
        if (!plugin.getConfig().isMsgIncomingSoundEnabled()) return
        val sound =
            Sound.sound(
                Key.key(plugin.getConfig().getMsgIncomingSoundKey()),
                Sound.Source.MASTER,
                plugin.getConfig().getMsgIncomingSoundVolume(),
                plugin.getConfig().getMsgIncomingSoundPitch(),
            )
        target.playSound(sound, Sound.Emitter.self())
    }

    private fun broadcastToSpies(
        senderComponent: Component,
        targetComponent: Component,
        message: Component,
        senderId: UUID,
        targetId: UUID,
    ) {
        if (socialSpies.isEmpty()) return
        val spyMessage =
            MINI_MESSAGE.deserialize(
                plugin.getConfig().getMsgSpyFormat(),
                Placeholder.component("sender", senderComponent),
                Placeholder.component("target", targetComponent),
                Placeholder.component("message", message),
            )
        for (spyId in socialSpies) {
            if (spyId == senderId || spyId == targetId) continue
            plugin.getServer().getPlayer(spyId).ifPresent { spy ->
                if (spy.hasPermission(SOCIALSPY_PERMISSION)) deliver(spy, spyMessage)
            }
        }
    }

    open fun deliver(player: Player, component: Component) {
        val bridge = plugin.getChatBridge()
        if (bridge == null || !bridge.deliver(player, component)) player.sendMessage(component)
    }

    private fun deliver(source: CommandSource, component: Component) {
        if (source is Player) deliver(source, component) else source.sendMessage(component)
    }

    open fun toggleSpy(uuid: UUID): Boolean {
        if (socialSpies.remove(uuid)) return false
        socialSpies.add(uuid)
        return true
    }

    open fun clearSpy(uuid: UUID) {
        socialSpies.remove(uuid)
    }

    open fun clearReplyTargets(uuid: UUID) {
        val partner = replyTargets.remove(uuid)
        if (partner != null) replyTargets.remove(partner, uuid)
    }

    private fun nameComponentFor(source: CommandSource): Component {
        if (source is Player) {
            val raw = plugin.getNicknameCache().getRawNickname(source.uniqueId)
            val click = ClickEvent.suggestCommand("/msg ${source.username} ")
            return (if (raw != null) NicknameComponentParser.parse(raw) else Component.text(source.username))
                .clickEvent(click)
        }
        return Component.text(CONSOLE_NAME)
    }

    companion object {
        @JvmField val CONSOLE_UUID = UUID(0L, 0L)
        const val SOCIALSPY_PERMISSION = "crabutilities.socialspy"
        const val BYPASS_DND_PERMISSION = "crabutilities.msg.bypassdnd"
        private const val CONSOLE_NAME = "Console"
        private const val NOT_ACCEPTING_MESSAGE = "<#f77069>That player isn't accepting private messages right now."
        private val MINI_MESSAGE = MiniMessage.miniMessage()
    }
}
