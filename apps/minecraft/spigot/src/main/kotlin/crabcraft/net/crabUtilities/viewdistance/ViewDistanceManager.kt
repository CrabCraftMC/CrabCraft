package crabcraft.net.crabUtilities.viewdistance

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import crabcraft.net.crabUtilities.CrabUtilities
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.scheduler.BukkitTask
import java.util.ArrayDeque
import java.util.UUID

/**
 * Adjusts each populated world's simulation and view distances using Paper's APIs.
 * Adapts ViewDistanceTweaks' reactive thresholds, sampling, cadence and hysteresis.
 * The upstream MIT notice is bundled at view-distance-tweaks/LICENSE.
 */
class ViewDistanceManager(private val plugin: CrabUtilities) : Listener {
    private val enabled: Boolean
    private val simulationBounds: Bounds
    private val viewBounds: Bounds
    private val tickDurations = TickDurationWindow(MSPT_SAMPLE_COUNT)
    private val adjustmentPolicy = AdjustmentPolicy()
    private val adjustmentSwitch = AdjustmentSwitch()
    private val clientViewRadiusBridge: ClientViewRadiusBridge
    private var initialClampTask: BukkitTask? = null
    private var adjustmentTask: BukkitTask? = null

    init {
        val settings = try {
            readSettings(plugin.getConfig())
        } catch (exception: IllegalArgumentException) {
            plugin.getLogger().warning("Invalid $CONFIG_ROOT configuration: " + exception.message + " — feature disabled.")
            Settings.disabled()
        }
        enabled = settings.enabled()
        simulationBounds = settings.simulationBounds()
        viewBounds = settings.viewBounds()
        clientViewRadiusBridge = ClientViewRadiusBridge(plugin, maxOf(simulationBounds.maximum(), viewBounds.maximum()))
    }

    fun isEnabled(): Boolean = enabled
    fun getMinimumSimulationDistance(): Int = simulationBounds.minimum()
    fun getMaximumSimulationDistance(): Int = simulationBounds.maximum()
    fun getMinimumViewDistance(): Int = viewBounds.minimum()
    fun getMaximumViewDistance(): Int = viewBounds.maximum()
    fun isPaused(): Boolean = adjustmentSwitch.isPaused()

    fun pause(): Boolean {
        if (!adjustmentSwitch.pause()) return false
        adjustmentPolicy.clear()
        tickDurations.clear()
        return true
    }

    fun resume(): Boolean {
        if (!adjustmentSwitch.resume()) return false
        adjustmentPolicy.clear()
        tickDurations.clear()
        return true
    }

    fun setManualSimulationDistance(world: World, distance: Int) {
        requireInBounds("simulation", distance, simulationBounds)
        setSimulationDistance(world, distance)
    }

    fun setManualViewDistance(world: World, distance: Int) {
        requireInBounds("view", distance, viewBounds)
        setViewDistance(world, distance)
    }

    fun start() {
        if (!enabled) return
        check(adjustmentTask == null) { "View distance manager is already running" }
        clientViewRadiusBridge.start()
        Bukkit.getPluginManager().registerEvents(this, plugin)
        initialClampTask = Bukkit.getScheduler().runTask(plugin, Runnable {
            initialClampTask = null
            for (world in Bukkit.getWorlds()) clampWorld(world, simulationBounds, viewBounds)
        })
        adjustmentTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { adjustDistances() },
            START_UP_DELAY_TICKS + CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS)
    }

    fun shutdown(restoreActualRadius: Boolean) {
        initialClampTask?.cancel()
        initialClampTask = null
        adjustmentTask?.cancel()
        adjustmentTask = null
        HandlerList.unregisterAll(this)
        clientViewRadiusBridge.shutdown(restoreActualRadius)
        adjustmentPolicy.clear()
        tickDurations.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTickEnd(event: ServerTickEndEvent) {
        if (!adjustmentSwitch.isPaused()) tickDurations.add(event.getTickDuration())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoad(event: WorldLoadEvent) { clampWorld(event.getWorld(), simulationBounds, viewBounds) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) { adjustmentPolicy.forget(event.getWorld().getUID()) }

    private fun adjustDistances() {
        if (adjustmentSwitch.isPaused()) return
        val mspt = tickDurations.median(EMPTY_SAMPLE_MSPT)
        for (world in Bukkit.getWorlds()) {
            if (world.getPlayers().isEmpty()) continue
            val adjustment = adjustmentPolicy.choose(world.getUID(), mspt)
            applyAdjustment(world, simulationBounds, viewBounds, adjustment)
        }
    }

    data class Settings(private val enabled: Boolean, private val simulationBounds: Bounds, private val viewBounds: Bounds) {
        fun enabled(): Boolean = enabled
        fun simulationBounds(): Bounds = simulationBounds
        fun viewBounds(): Bounds = viewBounds
        companion object {
            @JvmStatic
            fun disabled(): Settings = Settings(false, DEFAULT_SIMULATION_BOUNDS, DEFAULT_VIEW_BOUNDS)
        }
    }

    data class Bounds(private val minimum: Int, private val maximum: Int) {
        fun minimum(): Int = minimum
        fun maximum(): Int = maximum
        fun clamp(distance: Int): Int = maxOf(minimum, minOf(distance, maximum))
        fun contains(distance: Int): Boolean = distance >= minimum && distance <= maximum

        companion object {
            @JvmStatic
            fun checked(name: String, minimum: Int, maximum: Int): Bounds {
                require(minimum in MINIMUM_SUPPORTED_DISTANCE..MAXIMUM_SUPPORTED_DISTANCE && maximum in MINIMUM_SUPPORTED_DISTANCE..MAXIMUM_SUPPORTED_DISTANCE) {
                    "$name minimum and maximum must be between $MINIMUM_SUPPORTED_DISTANCE and $MAXIMUM_SUPPORTED_DISTANCE (got $minimum and $maximum)"
                }
                require(minimum <= maximum) { "$name minimum must not exceed its maximum (got $minimum and $maximum)" }
                return Bounds(minimum, maximum)
            }
        }
    }

    class AdjustmentSwitch {
        private var paused = false
        fun isPaused(): Boolean = paused
        fun pause(): Boolean {
            if (paused) return false
            paused = true
            return true
        }
        fun resume(): Boolean {
            if (!paused) return false
            paused = false
            return true
        }
    }

    enum class Adjustment(private val delta: Int) {
        INCREASE(1), DECREASE(-1), STAY(0);
        fun delta(): Int = delta
    }

    class AdjustmentPolicy {
        private val lowMsptCheckCounts = HashMap<UUID, Int>()
        fun choose(worldId: UUID, mspt: Double): Adjustment {
            if (!mspt.isFinite()) {
                lowMsptCheckCounts.remove(worldId)
                return Adjustment.STAY
            }
            if (mspt >= DECREASE_MSPT_THRESHOLD) {
                lowMsptCheckCounts.remove(worldId)
                return Adjustment.DECREASE
            }
            if (mspt <= INCREASE_MSPT_THRESHOLD) {
                val passedChecks = minOf(PASSED_CHECKS_FOR_INCREASE, lowMsptCheckCounts.getOrDefault(worldId, 0) + 1)
                lowMsptCheckCounts[worldId] = passedChecks
                return if (passedChecks >= PASSED_CHECKS_FOR_INCREASE) Adjustment.INCREASE else Adjustment.STAY
            }
            lowMsptCheckCounts.remove(worldId)
            return Adjustment.STAY
        }
        fun forget(worldId: UUID) { lowMsptCheckCounts.remove(worldId) }
        fun clear() { lowMsptCheckCounts.clear() }
    }

    class TickDurationWindow(private val capacity: Int) {
        private val durations: ArrayDeque<Double>
        init {
            require(capacity > 0) { "Tick duration capacity must be positive" }
            durations = ArrayDeque(capacity)
        }
        fun add(duration: Double) {
            if (!duration.isFinite() || duration < 0.0) return
            if (durations.size == capacity) durations.removeFirst()
            durations.addLast(duration)
        }
        fun median(emptyValue: Double): Double {
            if (durations.isEmpty()) return emptyValue
            val sorted = durations.toDoubleArray()
            sorted.sort()
            val middle = sorted.size / 2
            if (sorted.size % 2 == 0) return (sorted[middle - 1] + sorted[middle]) / 2.0
            return sorted[middle]
        }
        fun clear() { durations.clear() }
    }

    companion object {
        const val CONFIG_ROOT = "tweaks.view-distance"
        const val MINIMUM_SUPPORTED_DISTANCE = 2
        const val MAXIMUM_SUPPORTED_DISTANCE = 32
        const val MSPT_SAMPLE_COUNT = 1200
        const val CHECK_PERIOD_TICKS = 600L
        const val START_UP_DELAY_TICKS = 2400L
        const val INCREASE_MSPT_THRESHOLD = 40.0
        const val DECREASE_MSPT_THRESHOLD = 47.0
        const val PASSED_CHECKS_FOR_INCREASE = 10
        const val EMPTY_SAMPLE_MSPT = 25.0
        private val DEFAULT_SIMULATION_BOUNDS = Bounds(6, 12)
        private val DEFAULT_VIEW_BOUNDS = Bounds(8, 16)

        @JvmStatic
        fun clampWorld(world: World, simulationBounds: Bounds, viewBounds: Bounds) {
            setSimulationDistance(world, simulationBounds.clamp(world.getSimulationDistance()))
            setViewDistance(world, viewBounds.clamp(world.getViewDistance()))
        }

        @JvmStatic
        fun applyAdjustment(world: World, simulationBounds: Bounds, viewBounds: Bounds, adjustment: Adjustment) {
            val delta = adjustment.delta()
            setSimulationDistance(world, simulationBounds.clamp(world.getSimulationDistance() + delta))
            setViewDistance(world, viewBounds.clamp(world.getViewDistance() + delta))
        }

        private fun setSimulationDistance(world: World, distance: Int) {
            if (world.getSimulationDistance() != distance) world.setSimulationDistance(distance)
        }
        private fun setViewDistance(world: World, distance: Int) {
            if (world.getViewDistance() != distance) world.setViewDistance(distance)
        }

        @JvmStatic
        fun requireInBounds(name: String, distance: Int, bounds: Bounds) {
            require(bounds.contains(distance)) { "$name distance must be between ${bounds.minimum()} and ${bounds.maximum()}" }
        }

        private fun readBounds(config: FileConfiguration, section: String, defaults: Bounds): Bounds {
            val path = "$CONFIG_ROOT.$section"
            val minimum = readInteger(config, "$path.minimum", defaults.minimum())
            val maximum = readInteger(config, "$path.maximum", defaults.maximum())
            return Bounds.checked(section, minimum, maximum)
        }

        @JvmStatic
        fun readSettings(config: FileConfiguration): Settings {
            val enabledPath = "$CONFIG_ROOT.enabled"
            require(!config.contains(enabledPath) || config.isBoolean(enabledPath)) { "$enabledPath must be true or false" }
            if (!config.getBoolean(enabledPath, false)) return Settings.disabled()
            return Settings(true, readBounds(config, "simulation-distance", DEFAULT_SIMULATION_BOUNDS),
                readBounds(config, "view-distance", DEFAULT_VIEW_BOUNDS))
        }

        private fun readInteger(config: FileConfiguration, path: String, defaultValue: Int): Int {
            if (!config.contains(path)) return defaultValue
            require(config.isInt(path)) { "$path must be an integer" }
            return config.getInt(path)
        }
    }
}
