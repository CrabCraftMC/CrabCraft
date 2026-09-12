package crabcraft.net.crabUtilities.velocity.staffchat

import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.DiscordWebhook
import crabcraft.net.crabUtilities.velocity.NicknameComponentParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

open class StaffChatManager(private val plugin: CrabUtilitiesVelocity, private val redis: RedisStaffChat,
                            private val discordWebhook: DiscordWebhook?, private val discordAvatarUrlTemplate: String?) {
    private val disabledPlayers = ConcurrentHashMap.newKeySet<UUID>()
    open fun hasPermission(player: Player) = player.hasPermission(PERMISSION)
    open fun isEnabled(uuid: UUID) = !disabledPlayers.contains(uuid)
    open fun toggle(uuid: UUID): Boolean {
        if (disabledPlayers.remove(uuid)) return true
        disabledPlayers.add(uuid)
        return false
    }
    open fun sendMessage(senderName: String, senderUuid: UUID?, message: Component) {
        redis.publish(senderName, message)
        sendToDiscord(senderName, senderUuid, message)
    }
    private fun sendToDiscord(senderName: String, senderUuid: UUID?, message: Component) {
        if (discordWebhook == null) return
        val plainName = NicknameComponentParser.plain(senderName)
        val plainMessage = PlainTextComponentSerializer.plainText().serialize(message)
        var avatarUrl: String? = null
        if (senderUuid != null && !discordAvatarUrlTemplate.isNullOrEmpty()) avatarUrl = discordAvatarUrlTemplate.replace("{uuid}", senderUuid.toString())
        discordWebhook.send(plainMessage, plainName, avatarUrl)
    }
    open fun displayMessage(senderName: String, message: Component) {
        val format = plugin.getConfig().getStaffChatFormat()
        val senderComponent = NicknameComponentParser.parse(senderName)
        val component = MINI_MESSAGE.deserialize(format, Placeholder.component("sender", senderComponent), Placeholder.component("message", message))
        for (player in plugin.getServer().allPlayers) if (player.hasPermission(PERMISSION) && isEnabled(player.uniqueId)) plugin.getMessageManager().deliver(player, component)
        val plainSender = PlainTextComponentSerializer.plainText().serialize(senderComponent)
        val plainMessage = PlainTextComponentSerializer.plainText().serialize(message)
        plugin.getLogger().info("[StaffChat] {}: {}", plainSender, plainMessage)
    }
    companion object {
        const val PERMISSION = "crabutilities.staffchat"
        private val MINI_MESSAGE = MiniMessage.miniMessage()
    }
}
