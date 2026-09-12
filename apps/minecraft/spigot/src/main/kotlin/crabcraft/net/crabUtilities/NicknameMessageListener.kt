package crabcraft.net.crabUtilities

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.TranslationArgument
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.event.HoverEvent.ShowEntity
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

open class NicknameMessageListener(private val nicknameResolver: NicknameResolver) : Listener {
    constructor(plugin: CrabUtilities) : this(object : NicknameResolver {
        override fun byUuid(uuid: UUID): ResolvedNickname? = resolve(Bukkit.getPlayer(uuid))
        override fun byAccountName(accountName: String): ResolvedNickname? = resolve(Bukkit.getPlayerExact(accountName))
        private fun resolve(player: Player?): ResolvedNickname? {
            if (player == null) return null
            val nickname = NicknameComponentResolver.forPlayer(plugin.getEssentials(), player)
            return if (nickname == null) null else ResolvedNickname(player.getName(), nickname)
        }
    })

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onDeath(event: PlayerDeathEvent) {
        event.deathMessage()?.let { event.deathMessage(transformComponent(it)) }
        event.deathScreenMessageOverride()?.let { event.deathScreenMessageOverride(transformComponent(it)) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val msg = event.message() ?: return
        event.message(transformComponent(msg))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onJoin(event: PlayerJoinEvent) {
        // Velocity owns lifecycle broadcasts after its authoritative nickname seed completes.
        event.joinMessage(NO_BACKEND_LIFECYCLE_MESSAGE)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onQuit(event: PlayerQuitEvent) {
        event.quitMessage(NO_BACKEND_LIFECYCLE_MESSAGE)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onKick(event: PlayerKickEvent) {
        event.leaveMessage(NO_BACKEND_LIFECYCLE_MESSAGE)
    }

    fun transformComponent(input: Component): Component {
        val player = resolvePlayer(input)
        if (player != null) {
            // Replace the semantic player name before recursion to avoid duplicating it.
            return replacePlayerComponent(input, player)
        }
        var afterChildren = input
        if (input.children().isNotEmpty()) {
            val newChildren = ArrayList<Component>(input.children().size)
            for (child in input.children()) newChildren.add(transformComponent(child))
            afterChildren = input.children(newChildren)
        }
        if (afterChildren is TranslatableComponent) {
            val tc = afterChildren
            val newArgs = ArrayList<ComponentLike>(tc.arguments().size)
            for (arg in tc.arguments()) {
                val value = arg.value()
                if (value !is ComponentLike) {
                    newArgs.add(arg)
                    continue
                }
                newArgs.add(TranslationArgument.component(transformComponent(value.asComponent())))
            }
            afterChildren = tc.toBuilder().arguments(newArgs).build()
        }
        return afterChildren
    }

    private fun resolvePlayer(component: Component): ResolvedNickname? {
        val hover = component.style().hoverEvent()
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ENTITY) {
            val show = hover.value() as ShowEntity
            val player = nicknameResolver.byUuid(show.id())
            if (player != null) return player
        }
        if (component is TextComponent) {
            val content = component.content()
            if (content.isNotEmpty()) return nicknameResolver.byAccountName(content)
        }
        return null
    }

    private fun replacePlayerComponent(original: Component, player: ResolvedNickname): Component {
        val children = original.children()
        val nameSpan = findNameSpan(children, player.accountName())
        val accountNameAtRoot = original is TextComponent && original.content() == player.accountName()
        if (accountNameAtRoot || nameSpan != null) {
            val replacedChildren = ArrayList<Component>(children.size + 1)
            if (nameSpan == null) {
                replacedChildren.add(player.nickname())
                replacedChildren.addAll(children)
            } else {
                replacedChildren.addAll(children.subList(0, nameSpan.start()))
                replacedChildren.add(player.nickname())
                replacedChildren.addAll(children.subList(nameSpan.end(), children.size))
            }
            if (accountNameAtRoot) return (original as TextComponent).content("").children(replacedChildren)
            return original.children(replacedChildren)
        }
        if (PLAIN.serialize(original) == player.accountName()) {
            return Component.empty().style(original.style()).append(player.nickname())
        }
        // No account-name subtree means this wrapper was already transformed.
        return original
    }

    interface NicknameResolver {
        fun byUuid(uuid: UUID): ResolvedNickname?
        fun byAccountName(accountName: String): ResolvedNickname?
    }

    data class ResolvedNickname(private val accountName: String, private val nickname: Component) {
        fun accountName(): String = accountName
        fun nickname(): Component = nickname
    }

    private data class NameSpan(private val start: Int, private val end: Int) {
        fun start(): Int = start
        fun end(): Int = end
    }

    companion object {
        private val PLAIN = PlainTextComponentSerializer.plainText()
        private val NO_BACKEND_LIFECYCLE_MESSAGE = Component.empty()

        private fun findNameSpan(children: List<Component>, accountName: String): NameSpan? {
            for (i in children.indices) {
                if (PLAIN.serialize(children[i]) == accountName) return NameSpan(i, i + 1)
            }
            for (start in children.indices) {
                val candidate = StringBuilder()
                for (end in start until children.size) {
                    candidate.append(PLAIN.serialize(children[end]))
                    val text = candidate.toString()
                    if (text == accountName) return NameSpan(start, end + 1)
                    if (!accountName.startsWith(text)) break
                }
            }
            return null
        }
    }
}
