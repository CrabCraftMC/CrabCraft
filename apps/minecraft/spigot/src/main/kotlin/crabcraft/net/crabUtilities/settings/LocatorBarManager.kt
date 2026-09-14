package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabUtilities
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.keys.GameRuleKeys
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.WorldLoadEvent
import java.util.UUID

/** Keeps the locator gamerule enabled and controls each player's waypoint receive range. */
open class LocatorBarManager(private val plugin: CrabUtilities, private val settingsService: PlayerSettingsService) : Listener {
    init { settingsService.addListener(::onSettingsChanged) }

    open fun start() {
        for (world in Bukkit.getWorlds()) enableLocatorBarGameRule(world)
        for (player in Bukkit.getOnlinePlayers()) apply(player, false)
        plugin.getLogger().info("Locator bar manager active: world gamerule enabled, players opt in to viewing via /settings.")
    }

    @EventHandler
    open fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.getPlayer()
        enableLocatorBarGameRule(player.getWorld())
        // Default to not receiving until the async settings load reapplies the saved preference.
        apply(player, false)
        val uuid = player.getUniqueId()
        if (settingsService.isLoaded(uuid)) apply(player, settingsService.isLocatorBarEnabled(uuid))
    }

    @EventHandler
    open fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.getPlayer()
        enableLocatorBarGameRule(player.getWorld())
        val uuid = player.getUniqueId()
        apply(player, settingsService.isLoaded(uuid) && settingsService.isLocatorBarEnabled(uuid))
    }

    @EventHandler
    open fun onWorldLoad(event: WorldLoadEvent) { enableLocatorBarGameRule(event.getWorld()) }

    private fun onSettingsChanged(uuid: UUID, settings: PlayerSettings) {
        val task = Runnable {
            val player = Bukkit.getPlayer(uuid)
            if (player != null) {
                enableLocatorBarGameRule(player.getWorld())
                apply(player, settings.isLocatorBar())
            }
        }
        if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
    }

    private fun apply(player: Player, enabled: Boolean) {
        setAttribute(player, Attribute.WAYPOINT_TRANSMIT_RANGE, ENABLED_RANGE)
        setAttribute(player, Attribute.WAYPOINT_RECEIVE_RANGE, if (enabled) ENABLED_RANGE else DISABLED_RECEIVE_RANGE)
        if (!enabled) keepTransmittingWaypoint(player)
    }

    private fun setAttribute(player: Player, attribute: Attribute, value: Double) {
        val instance = player.getAttribute(attribute) ?: return
        if (java.lang.Double.compare(instance.getBaseValue(), value) != 0) instance.setBaseValue(value)
    }

    private fun keepTransmittingWaypoint(player: Player) {
        val handle = (player as CraftPlayer).getHandle()
        val waypointManager = handle.level().getWaypointManager()
        // Setting receive range to zero also untracks the waypoint; restore its transmission.
        waypointManager.untrackWaypoint(handle)
        if (handle.isTransmittingWaypoint()) waypointManager.trackWaypoint(handle)
    }

    private fun enableLocatorBarGameRule(world: World) {
        val locatorBar = locatorBarGameRule()
        if (world.getGameRuleValue(locatorBar) != true) world.setGameRule(locatorBar, true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun locatorBarGameRule(): GameRule<Boolean> = RegistryAccess.registryAccess()
        .getRegistry(RegistryKey.GAME_RULE).getOrThrow(GameRuleKeys.LOCATOR_BAR) as GameRule<Boolean>

    companion object {
        private const val ENABLED_RANGE = 60_000_000.0
        private const val DISABLED_RECEIVE_RANGE = 0.0
    }
}
