package crabcraft.net.crabUtilities.sleep

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.NicknameComponentResolver
import crabcraft.net.crabUtilities.PlayerVisibility
import java.lang.reflect.Method
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.TimeSkipEvent

/** Announces visible sleepers using component placeholders that preserve styled nicknames. */
open class SleepBroadcastListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(ignoreCancelled = true)
    open fun onTimeSkip(event: TimeSkipEvent) {
        if (isKnownNotNightSkip(event) || !plugin.config.getBoolean("tweaks.sleep-broadcast.enabled", false)) return
        val sleepers = event.world.players.filter { it.isSleeping }
        if (sleepers.isEmpty()) return
        for (viewer in Bukkit.getOnlinePlayers()) {
            val visibleNames =
                PlayerVisibility.visibleTo(viewer, sleepers).map {
                    NicknameComponentResolver.forPlayer(plugin.getEssentials(), it) ?: Component.text(it.name)
                }
            if (visibleNames.isEmpty()) continue
            val format =
                if (visibleNames.size == 1)
                    plugin.config.getString("tweaks.sleep-broadcast.single-format", DEFAULT_SINGLE_FORMAT)!!
                else plugin.config.getString("tweaks.sleep-broadcast.multiple-format", DEFAULT_MULTIPLE_FORMAT)!!
            viewer.sendMessage(formatMessage(format, visibleNames))
        }
    }

    companion object {
        private val DEFAULT_SINGLE_FORMAT =
            CrabMessages.HIGHLIGHT_TAG + "<player>" + CrabMessages.TEXT_TAG + " slept to skip the night."
        private val DEFAULT_MULTIPLE_FORMAT =
            CrabMessages.HIGHLIGHT_TAG + "<players>" + CrabMessages.TEXT_TAG + " slept to skip the night."
        private val MINI_MESSAGE = MiniMessage.miniMessage()
        // Reflect by name: some forks change this method's return descriptor.
        private val GET_SKIP_REASON: Method? =
            try {
                TimeSkipEvent::class.java.getMethod("getSkipReason")
            } catch (_: NoSuchMethodException) {
                null
            } catch (_: LinkageError) {
                null
            }

        private fun isKnownNotNightSkip(event: TimeSkipEvent): Boolean {
            val method = GET_SKIP_REASON ?: return false
            return try {
                val reason = method.invoke(event)
                reason != null && reason.toString() != "NIGHT_SKIP"
            } catch (_: ReflectiveOperationException) {
                false
            } catch (_: LinkageError) {
                false
            }
        }

        @JvmStatic
        fun formatMessage(format: String, sleepers: List<Component>): Component =
            if (sleepers.size == 1) MINI_MESSAGE.deserialize(format, Placeholder.component("player", sleepers[0]))
            else
                MINI_MESSAGE.deserialize(
                    format,
                    Placeholder.component("players", joinNames(sleepers)),
                    Placeholder.unparsed("count", sleepers.size.toString()),
                )

        @JvmStatic
        fun joinNames(names: List<Component>): Component {
            if (names.size == 2)
                return Component.empty().append(names[0]).append(Component.text(" and ")).append(names[1])
            var result: Component = Component.empty()
            for (i in names.indices) {
                if (i > 0) result = result.append(Component.text(", "))
                if (i == names.lastIndex) result = result.append(Component.text("and "))
                result = result.append(names[i])
            }
            return result
        }
    }
}
