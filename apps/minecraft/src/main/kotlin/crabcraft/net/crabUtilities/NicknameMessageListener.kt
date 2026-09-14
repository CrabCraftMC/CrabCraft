package crabcraft.net.crabUtilities

import com.earth2me.essentials.Essentials
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.TranslationArgument
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

open class NicknameMessageListener(private val plugin: CrabUtilities) : Listener {
    // Legacy serializer supporting &-codes and hex (both &#RRGGBB and &x&R&R&G&G&B&B).
    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors()
        .useUnusualXRepeatedCharacterHexFormat().build()
    private val mm = MiniMessage.miniMessage()

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onDeath(event: PlayerDeathEvent) {
        val message = event.deathMessage() ?: return
        event.deathMessage(transformComponent(message))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val message = event.message() ?: return
        event.message(transformComponent(message))
    }

    private fun transformComponent(input: Component): Component {
        // Rebuild recursively, replacing player-entity components with their nick component.
        var afterChildren = input
        if (input.children().isNotEmpty()) afterChildren = input.children(input.children().map(::transformComponent))
        // Death/advancement messages are translatable, so transform their arguments too.
        if (afterChildren is TranslatableComponent) {
            afterChildren = afterChildren.toBuilder().arguments(afterChildren.arguments().map {
                val value = it.value()
                if (value is ComponentLike) {
                    TranslationArgument.component(maybeReplacePlayerNameComponent(transformComponent(value.asComponent())))
                } else it
            }).build()
        }
        return maybeReplacePlayerNameComponent(afterChildren)
    }

    private fun maybeReplacePlayerNameComponent(component: Component): Component {
        val style = component.style()
        val hover = style.hoverEvent()
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ENTITY) {
            val show = hover.value() as HoverEvent.ShowEntity
            val player = Bukkit.getPlayer(show.id())
            if (player != null) {
                val nick = nicknameFor(player)
                if (nick != null) {
                    // Preserve interactions and font while letting the nickname supply colour/formatting.
                    var output = nick.hoverEvent(hover)
                    style.clickEvent()?.let { output = output.clickEvent(it) }
                    style.insertion()?.let { output = output.insertion(it) }
                    style.font()?.let { output = output.font(it) }
                    // Original children can contain per-character text and would duplicate the name.
                    return output
                }
            }
        }
        // Fall back to matching a plain text component against an online player's exact name.
        if (component is TextComponent && component.content().isNotEmpty()) {
            val player = Bukkit.getPlayerExact(component.content())
            if (player != null) {
                val nick = nicknameFor(player)
                if (nick != null) {
                    var output: Component = nick
                    style.hoverEvent()?.let { output = output.hoverEvent(it) }
                    style.clickEvent()?.let { output = output.clickEvent(it) }
                    style.insertion()?.let { output = output.insertion(it) }
                    style.font()?.let { output = output.font(it) }
                    return output
                }
            }
        }
        return component
    }

    private fun nicknameFor(player: Player): Component? {
        val essentials = plugin.getEssentials() as? Essentials ?: return null
        val user = essentials.getUser(player) ?: return null
        val raw = user.nickname?.takeUnless { it.isBlank() } ?: return null
        if (looksLikeMiniMessage(raw)) {
            try {
                return mm.deserialize(raw)
            } catch (ignored: Exception) {
                // Fall through to legacy if MiniMessage parsing fails.
            }
        }
        val processed = convertAmpersandHex(raw.replace('§', '&'))
        return try {
            legacy.deserialize(processed)
        } catch (exception: Exception) {
            Component.text(raw)
        }
    }

    companion object {
        private val AMP_HEX_PATTERN = Pattern.compile("&[#]([0-9a-fA-F]{6})")
        private fun looksLikeMiniMessage(value: String): Boolean = value.contains("<#") || value.contains("</gradient>") ||
            value.contains("<gradient:") || value.contains("<rainbow>") || value.contains("</rainbow>")
        private fun convertAmpersandHex(input: String): String {
            val buffer = StringBuffer()
            val matcher = AMP_HEX_PATTERN.matcher(input)
            while (matcher.find()) {
                val hex = matcher.group(1).uppercase(Locale.ROOT)
                val replacement = "&x&${hex[0]}&${hex[1]}&${hex[2]}&${hex[3]}&${hex[4]}&${hex[5]}"
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement))
            }
            matcher.appendTail(buffer)
            return buffer.toString()
        }
    }
}
