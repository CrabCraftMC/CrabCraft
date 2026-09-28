package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabUtilities
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.keys.GameRuleKeys
import java.util.UUID
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

/** Enables the world gamerule and controls locator bar visibility with per-player attributes. */
open class LocatorBarManager(
    private val plugin: CrabUtilities,
    private val settingsService: PlayerSettingsService,
) : Listener {
    init {
        settingsService.addListener(::onSettingsChanged)
    }

    open fun start() {
        for (world in Bukkit.getWorlds()) enableLocatorBarGameRule(world)
        for (player in Bukkit.getOnlinePlayers()) apply(player, false)
        plugin
            .getLogger()
            .info("Locator bar manager active: world gamerule enabled, players opt in to viewing via /settings.")
    }

    @EventHandler
    open fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        enableLocatorBarGameRule(player.world)
        // Hide waypoints until the async settings load reapplies the saved preference.
        apply(player, false)
        val uuid = player.uniqueId
        if (settingsService.isLoaded(uuid)) apply(player, settingsService.isLocatorBarEnabled(uuid))
    }

    @EventHandler
    open fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.player
        enableLocatorBarGameRule(player.world)
        val uuid = player.uniqueId
        apply(player, settingsService.isLoaded(uuid) && settingsService.isLocatorBarEnabled(uuid))
    }

    @EventHandler open fun onWorldLoad(event: WorldLoadEvent) = enableLocatorBarGameRule(event.world)

    private fun onSettingsChanged(uuid: UUID, settings: PlayerSettings) {
        val task = Runnable {
            Bukkit.getPlayer(uuid)?.let { player ->
                enableLocatorBarGameRule(player.world)
                apply(player, settings.isLocatorBar())
            }
        }
        if (Bukkit.isPrimaryThread()) task.run() else Bukkit.getScheduler().runTask(plugin, task)
    }

    private fun apply(player: Player, enabled: Boolean) {
        val vanished = plugin.isVanished(player)
        setAttribute(
            player,
            Attribute.WAYPOINT_TRANSMIT_RANGE,
            if (vanished) DISABLED_RECEIVE_RANGE else ENABLED_RANGE,
        )
        setAttribute(
            player,
            Attribute.WAYPOINT_RECEIVE_RANGE,
            if (enabled) ENABLED_RANGE else DISABLED_RECEIVE_RANGE,
        )
        if (!enabled && !vanished) keepTransmittingWaypoint(player)
    }

    /** Reapplies visibility immediately after an EssentialsX vanish change. */
    open fun refresh(player: Player) {
        val uuid = player.uniqueId
        apply(player, settingsService.isLoaded(uuid) && settingsService.isLocatorBarEnabled(uuid))
    }

    private fun setAttribute(player: Player, attribute: Attribute, value: Double) {
        val instance = player.getAttribute(attribute) ?: return
        if (java.lang.Double.compare(instance.baseValue, value) != 0) instance.baseValue = value
    }

    private fun keepTransmittingWaypoint(player: Player) {
        val handle = (player as CraftPlayer).handle
        val waypointManager = handle.level().getWaypointManager()
        // A zero receive range also untracks this player's waypoint; restore it for other viewers.
        waypointManager.untrackWaypoint(handle)
        if (handle.isTransmittingWaypoint()) waypointManager.trackWaypoint(handle)
    }

    private fun enableLocatorBarGameRule(world: World) {
        val locatorBar = locatorBarGameRule()
        if (world.getGameRuleValue(locatorBar) != true) world.setGameRule(locatorBar, true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun locatorBarGameRule(): GameRule<Boolean> =
        RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE).getOrThrow(GameRuleKeys.LOCATOR_BAR)
            as GameRule<Boolean>

    companion object {
        private const val ENABLED_RANGE = 60_000_000.0
        private const val DISABLED_RECEIVE_RANGE = 0.0
    }
}
