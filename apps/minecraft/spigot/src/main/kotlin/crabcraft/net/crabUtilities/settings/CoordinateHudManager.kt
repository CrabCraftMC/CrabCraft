package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabMessages
import crabcraft.net.crabUtilities.CrabUtilities
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Refreshes coordinates and facing direction in the action bar, including for stationary players. */
open class CoordinateHudManager(private val plugin: CrabUtilities, private val settingsService: PlayerSettingsService) : Listener {
    private val activeTasks = ConcurrentHashMap<UUID, BukkitTask>()

    @EventHandler
    open fun onJoin(event: PlayerJoinEvent) { startTaskFor(event.getPlayer()) }

    @EventHandler
    open fun onQuit(event: PlayerQuitEvent) { stopTaskFor(event.getPlayer()) }

    open fun start() { Bukkit.getOnlinePlayers().forEach(::startTaskFor) }

    open fun shutdown() {
        activeTasks.values.forEach(BukkitTask::cancel)
        activeTasks.clear()
    }

    private fun startTaskFor(player: Player) {
        val uuid = player.getUniqueId()
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!player.isOnline()) {
                stopTaskFor(player)
                return@Runnable
            }
            if (!isCoordinateHudEnabled(player)) return@Runnable
            sendCoordinateActionBar(player)
        }, 0L, REFRESH_INTERVAL_TICKS)
        activeTasks[uuid] = task
    }

    private fun stopTaskFor(player: Player) { activeTasks.remove(player.getUniqueId())?.cancel() }

    private fun sendCoordinateActionBar(player: Player) {
        val x = player.getLocation().getBlockX()
        val y = player.getLocation().getBlockY()
        val z = player.getLocation().getBlockZ()
        val facing = getFacingDirection(player.getLocation().getYaw())
        val message = Component.text("$x $y $z", CrabMessages.TEXT)
            .append(Component.text(" ($facing)", CrabMessages.MUTED))
        player.sendActionBar(message)
    }

    /** Minecraft yaw: 0/360 is south, 90 west, 180 north and 270 east. */
    private fun getFacingDirection(yaw: Float): String {
        val normalizedYaw = (yaw % 360f + 360f) % 360f
        val directions = arrayOf("S", "SW", "W", "NW", "N", "NE", "E", "SE")
        val index = Math.round(normalizedYaw / 45f) % 8
        return directions[index]
    }

    private fun isCoordinateHudEnabled(player: Player): Boolean = settingsService.isCoordinateHudEnabled(player.getUniqueId())

    companion object { private const val REFRESH_INTERVAL_TICKS = 2L }
}
