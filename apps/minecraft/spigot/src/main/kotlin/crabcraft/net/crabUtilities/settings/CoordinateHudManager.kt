package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.CrabUtilities
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/** One repeating task refreshes enabled action bars, even while players stand still. */
open class CoordinateHudManager(
    private val plugin: CrabUtilities,
    private val settingsService: PlayerSettingsService,
) {
    private var refreshTask: BukkitTask? = null

    open fun start() {
        refreshTask =
            Bukkit.getScheduler()
                .runTaskTimer(
                    plugin,
                    Runnable {
                        for (player in Bukkit.getOnlinePlayers()) {
                            if (settingsService.isCoordinateHudEnabled(player.uniqueId)) sendCoordinateActionBar(player)
                        }
                    },
                    0L,
                    REFRESH_INTERVAL_TICKS,
                )
    }

    open fun shutdown() {
        refreshTask?.cancel()
        refreshTask = null
    }

    private fun sendCoordinateActionBar(player: Player) {
        val location = player.location
        val facing = getFacingDirection(location.yaw)
        val message =
            Component.text("${location.blockX} ${location.blockY} ${location.blockZ}", CrabMessages.TEXT)
                .append(Component.text(" ($facing)", CrabMessages.MUTED))
        player.sendActionBar(message)
    }

    /** Minecraft yaw: 0/360 = South, 90 = West, 180 = North, 270 = East. */
    private fun getFacingDirection(yaw: Float): String {
        val normalisedYaw = (yaw % 360f + 360f) % 360f
        return DIRECTIONS[Math.round(normalisedYaw / 45f) % 8]
    }

    companion object {
        private const val REFRESH_INTERVAL_TICKS = 2L
        private val DIRECTIONS = arrayOf("S", "SW", "W", "NW", "N", "NE", "E", "SE")
    }
}
