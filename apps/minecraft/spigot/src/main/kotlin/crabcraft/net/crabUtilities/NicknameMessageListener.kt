package crabcraft.net.crabUtilities

import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.TranslationArgument
import net.kyori.adventure.text.event.HoverEvent
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

open class NicknameMessageListener(private val nicknameResolver: NicknameResolver) : Listener {
    constructor(
        plugin: CrabUtilities
    ) : this(
        object : NicknameResolver {
            override fun byUuid(uuid: UUID) = resolve(Bukkit.getPlayer(uuid))

            override fun byAccountName(accountName: String) = resolve(Bukkit.getPlayerExact(accountName))

            private fun resolve(player: Player?): ResolvedNickname? {
                if (player == null) return null
                val nickname = NicknameComponentResolver.forPlayer(plugin.getEssentials(), player) ?: return null
                return ResolvedNickname(player.name, nickname)
            }
        }
    )

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onDeath(event: PlayerDeathEvent) {
        event.deathMessage()?.let { event.deathMessage(transformComponent(it)) }
        event.deathScreenMessageOverride()?.let { event.deathScreenMessageOverride(transformComponent(it)) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        event.message()?.let { event.message(transformComponent(it)) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onJoin(event: PlayerJoinEvent) {
        // Velocity owns lifecycle broadcasts after its authoritative nickname seed.
        event.joinMessage(NO_BACKEND_LIFECYCLE_MESSAGE)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    open fun onQuit(event: PlayerQuitEvent) = event.quitMessage(NO_BACKEND_LIFECYCLE_MESSAGE)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    open fun onKick(event: PlayerKickEvent) = event.leaveMessage(NO_BACKEND_LIFECYCLE_MESSAGE)

    open fun transformComponent(input: Component): Component {
        val player = resolvePlayer(input)
        // Replace the semantic player wrapper before recursing to avoid replacing it twice.
        if (player != null) return replacePlayerComponent(input, player)
        var afterChildren =
            if (input.children().isEmpty()) input else input.children(input.children().map(::transformComponent))
        if (afterChildren is TranslatableComponent) {
            val newArgs = ArrayList<ComponentLike>(afterChildren.arguments().size)
            for (arg in afterChildren.arguments()) {
                val value = arg.value()
                newArgs.add(
                    if (value is ComponentLike) TranslationArgument.component(transformComponent(value.asComponent()))
                    else arg
                )
            }
            afterChildren = afterChildren.toBuilder().arguments(newArgs).build()
        }
        return afterChildren
    }

    private fun resolvePlayer(component: Component): ResolvedNickname? {
        val hover = component.style().hoverEvent()
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ENTITY) {
            val show = hover.value() as HoverEvent.ShowEntity
            nicknameResolver.byUuid(show.id())?.let {
                return it
            }
        }
        if (component is TextComponent && component.content().isNotEmpty())
            return nicknameResolver.byAccountName(component.content())
        return null
    }

    private fun replacePlayerComponent(original: Component, player: ResolvedNickname): Component {
        val children = original.children()
        val nameSpan = findNameSpan(children, player.accountName)
        val accountNameAtRoot = original is TextComponent && original.content() == player.accountName
        if (accountNameAtRoot || nameSpan != null) {
            val replacedChildren = ArrayList<Component>(children.size + 1)
            if (nameSpan == null) {
                replacedChildren.add(player.nickname)
                replacedChildren.addAll(children)
            } else {
                replacedChildren.addAll(children.subList(0, nameSpan.start))
                replacedChildren.add(player.nickname)
                replacedChildren.addAll(children.subList(nameSpan.end, children.size))
            }
            return if (accountNameAtRoot) (original as TextComponent).content("").children(replacedChildren)
            else original.children(replacedChildren)
        }
        if (PLAIN.serialize(original) == player.accountName)
            return Component.empty().style(original.style()).append(player.nickname)
        // A wrapper without an account-name subtree has already been transformed.
        return original
    }

    interface NicknameResolver {
        fun byUuid(uuid: UUID): ResolvedNickname?

        fun byAccountName(accountName: String): ResolvedNickname?
    }

    data class ResolvedNickname(val accountName: String, val nickname: Component) {
        fun accountName(): String = accountName

        fun nickname(): Component = nickname
    }

    private data class NameSpan(val start: Int, val end: Int)

    companion object {
        private val PLAIN = PlainTextComponentSerializer.plainText()
        private val NO_BACKEND_LIFECYCLE_MESSAGE = Component.empty()

        private fun findNameSpan(children: List<Component>, accountName: String): NameSpan? {
            for (i in children.indices) if (PLAIN.serialize(children[i]) == accountName) return NameSpan(i, i + 1)
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
