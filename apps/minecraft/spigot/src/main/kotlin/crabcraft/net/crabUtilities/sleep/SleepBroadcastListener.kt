package crabcraft.net.crabUtilities.sleep

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.NicknameComponentResolver
import crabcraft.net.crabUtilities.PlayerVisibility
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.TimeSkipEvent
import java.lang.reflect.Method
import java.util.ArrayList

/** Announces sleepers using styled component placeholders and viewer visibility. */
open class SleepBroadcastListener(private val plugin: CrabUtilities) : Listener {
    @EventHandler(ignoreCancelled = true)
    open fun onTimeSkip(event: TimeSkipEvent) {
        if (isKnownNotNightSkip(event)) return
        if (!plugin.getConfig().getBoolean("tweaks.sleep-broadcast.enabled", false)) return
        val sleepers = event.getWorld().getPlayers().filter { it.isSleeping }
        // Also rejects command/plugin skips if the fork's skip reason cannot be resolved.
        if (sleepers.isEmpty()) return
        for (viewer in Bukkit.getOnlinePlayers()) {
            val visibleNames = PlayerVisibility.visibleTo(viewer, sleepers).map { sleeper ->
                NicknameComponentResolver.forPlayer(plugin.getEssentials(), sleeper) ?: Component.text(sleeper.getName())
            }
            if (visibleNames.isEmpty()) continue
            val format = if (visibleNames.size == 1) plugin.getConfig().getString("tweaks.sleep-broadcast.single-format", DEFAULT_SINGLE_FORMAT)
                else plugin.getConfig().getString("tweaks.sleep-broadcast.multiple-format", DEFAULT_MULTIPLE_FORMAT)
            viewer.sendMessage(formatMessage(format!!, visibleNames))
        }
    }
    companion object {
        private const val DEFAULT_SINGLE_FORMAT = CrabMessages.HIGHLIGHT_TAG + "<player>" + CrabMessages.TEXT_TAG + " slept to skip the night."
        private const val DEFAULT_MULTIPLE_FORMAT = CrabMessages.HIGHLIGHT_TAG + "<players>" + CrabMessages.TEXT_TAG + " slept to skip the night."
        private val MINI_MESSAGE = MiniMessage.miniMessage()
        // Match by name and parameters: some forks change the method's return type.
        private val GET_SKIP_REASON: Method? = resolveGetSkipReason()
        private fun resolveGetSkipReason(): Method? = try { TimeSkipEvent::class.java.getMethod("getSkipReason") }
            catch (e: NoSuchMethodException) { null } catch (e: LinkageError) { null }
        private fun isKnownNotNightSkip(event: TimeSkipEvent): Boolean {
            val method = GET_SKIP_REASON ?: return false
            return try {
                val reason = method.invoke(event)
                reason != null && reason.toString() != "NIGHT_SKIP"
            } catch (e: ReflectiveOperationException) { false } catch (e: LinkageError) { false }
        }
        @JvmStatic
        fun formatMessage(format: String, sleepers: List<Component>): Component {
            if (sleepers.size == 1) return MINI_MESSAGE.deserialize(format, Placeholder.component("player", sleepers[0]))
            return MINI_MESSAGE.deserialize(format, Placeholder.component("players", joinNames(sleepers)), Placeholder.unparsed("count", sleepers.size.toString()))
        }
        @JvmStatic
        fun joinNames(names: List<Component>): Component {
            val size = names.size
            if (size == 2) return Component.empty().append(names[0]).append(Component.text(" and ")).append(names[1])
            var result: Component = Component.empty()
            for (i in 0 until size) {
                if (i > 0) result = result.append(Component.text(", "))
                if (i == size - 1) result = result.append(Component.text("and "))
                result = result.append(names[i])
            }
            return result
        }
    }
}
