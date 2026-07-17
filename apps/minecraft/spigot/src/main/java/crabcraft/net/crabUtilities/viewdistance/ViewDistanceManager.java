package crabcraft.net.crabUtilities.viewdistance;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Reactively adjusts each populated world's simulation and view distances.
 *
 * <p>This is a Paper-native, configuration-light adaptation of
 * <a href="https://github.com/froobynooby/ViewDistanceTweaks">ViewDistanceTweaks</a>'
 * reactive mode. It keeps that plugin's MSPT thresholds, sample window,
 * cadence, and increase hysteresis while using Paper's public world APIs
 * directly. The upstream MIT notice is bundled at
 * {@code view-distance-tweaks/LICENSE}.
 */
public final class ViewDistanceManager implements Listener {

    static final String CONFIG_ROOT = "tweaks.view-distance";
    static final int MINIMUM_SUPPORTED_DISTANCE = 2;
    static final int MAXIMUM_SUPPORTED_DISTANCE = 32;
    static final int MSPT_SAMPLE_COUNT = 1200;
    static final long CHECK_PERIOD_TICKS = 600L;
    static final long START_UP_DELAY_TICKS = 2400L;
    static final double INCREASE_MSPT_THRESHOLD = 40.0;
    static final double DECREASE_MSPT_THRESHOLD = 47.0;
    static final int PASSED_CHECKS_FOR_INCREASE = 10;
    static final double EMPTY_SAMPLE_MSPT = 25.0;

    private static final Bounds DEFAULT_SIMULATION_BOUNDS = new Bounds(6, 12);
    private static final Bounds DEFAULT_VIEW_BOUNDS = new Bounds(8, 16);

    private final CrabUtilities plugin;
    private final boolean enabled;
    private final Bounds simulationBounds;
    private final Bounds viewBounds;
    private final TickDurationWindow tickDurations = new TickDurationWindow(MSPT_SAMPLE_COUNT);
    private final AdjustmentPolicy adjustmentPolicy = new AdjustmentPolicy();
    private BukkitTask adjustmentTask;

    public ViewDistanceManager(CrabUtilities plugin) {
        this.plugin = plugin;

        Settings settings;
        try {
            settings = readSettings(plugin.getConfig());
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid " + CONFIG_ROOT + " configuration: "
                    + exception.getMessage() + " — feature disabled.");
            settings = Settings.disabled();
        }

        this.enabled = settings.enabled();
        this.simulationBounds = settings.simulationBounds();
        this.viewBounds = settings.viewBounds();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMinimumSimulationDistance() {
        return simulationBounds.minimum();
    }

    public int getMaximumSimulationDistance() {
        return simulationBounds.maximum();
    }

    public int getMinimumViewDistance() {
        return viewBounds.minimum();
    }

    public int getMaximumViewDistance() {
        return viewBounds.maximum();
    }

    public void start() {
        if (!enabled) {
            return;
        }
        if (adjustmentTask != null) {
            throw new IllegalStateException("View distance manager is already running");
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (World world : Bukkit.getWorlds()) {
            clampWorld(world, simulationBounds, viewBounds);
        }

        adjustmentTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::adjustDistances,
                START_UP_DELAY_TICKS + CHECK_PERIOD_TICKS,
                CHECK_PERIOD_TICKS);
    }

    public void shutdown() {
        if (adjustmentTask != null) {
            adjustmentTask.cancel();
            adjustmentTask = null;
        }
        HandlerList.unregisterAll(this);
        adjustmentPolicy.clear();
        tickDurations.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        tickDurations.add(event.getTickDuration());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        clampWorld(event.getWorld(), simulationBounds, viewBounds);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        adjustmentPolicy.forget(event.getWorld().getUID());
    }

    private void adjustDistances() {
        double mspt = tickDurations.median(EMPTY_SAMPLE_MSPT);
        for (World world : Bukkit.getWorlds()) {
            if (world.getPlayers().isEmpty()) {
                continue;
            }
            Adjustment adjustment = adjustmentPolicy.choose(world.getUID(), mspt);
            applyAdjustment(world, simulationBounds, viewBounds, adjustment);
        }
    }

    static void clampWorld(World world, Bounds simulationBounds, Bounds viewBounds) {
        setSimulationDistance(world, simulationBounds.clamp(world.getSimulationDistance()));
        setViewDistance(world, viewBounds.clamp(world.getViewDistance()));
    }

    static void applyAdjustment(
            World world,
            Bounds simulationBounds,
            Bounds viewBounds,
            Adjustment adjustment) {
        int delta = adjustment.delta();
        setSimulationDistance(world, simulationBounds.clamp(world.getSimulationDistance() + delta));
        setViewDistance(world, viewBounds.clamp(world.getViewDistance() + delta));
    }

    private static void setSimulationDistance(World world, int distance) {
        if (world.getSimulationDistance() != distance) {
            world.setSimulationDistance(distance);
        }
    }

    private static void setViewDistance(World world, int distance) {
        if (world.getViewDistance() != distance) {
            world.setViewDistance(distance);
        }
    }

    private static Bounds readBounds(
            FileConfiguration config,
            String section,
            Bounds defaults) {
        String path = CONFIG_ROOT + "." + section;
        int minimum = readInteger(config, path + ".minimum", defaults.minimum());
        int maximum = readInteger(config, path + ".maximum", defaults.maximum());
        return Bounds.checked(section, minimum, maximum);
    }

    static Settings readSettings(FileConfiguration config) {
        String enabledPath = CONFIG_ROOT + ".enabled";
        if (config.contains(enabledPath) && !config.isBoolean(enabledPath)) {
            throw new IllegalArgumentException(enabledPath + " must be true or false");
        }
        if (!config.getBoolean(enabledPath, false)) {
            return Settings.disabled();
        }
        return new Settings(
                true,
                readBounds(config, "simulation-distance", DEFAULT_SIMULATION_BOUNDS),
                readBounds(config, "view-distance", DEFAULT_VIEW_BOUNDS));
    }

    private static int readInteger(FileConfiguration config, String path, int defaultValue) {
        if (!config.contains(path)) {
            return defaultValue;
        }
        if (!config.isInt(path)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        return config.getInt(path);
    }

    record Settings(boolean enabled, Bounds simulationBounds, Bounds viewBounds) {
        static Settings disabled() {
            return new Settings(false, DEFAULT_SIMULATION_BOUNDS, DEFAULT_VIEW_BOUNDS);
        }
    }

    record Bounds(int minimum, int maximum) {
        static Bounds checked(String name, int minimum, int maximum) {
            if (minimum < MINIMUM_SUPPORTED_DISTANCE || minimum > MAXIMUM_SUPPORTED_DISTANCE
                    || maximum < MINIMUM_SUPPORTED_DISTANCE || maximum > MAXIMUM_SUPPORTED_DISTANCE) {
                throw new IllegalArgumentException(name + " minimum and maximum must be between "
                        + MINIMUM_SUPPORTED_DISTANCE + " and " + MAXIMUM_SUPPORTED_DISTANCE
                        + " (got " + minimum + " and " + maximum + ")");
            }
            if (minimum > maximum) {
                throw new IllegalArgumentException(name + " minimum must not exceed its maximum"
                        + " (got " + minimum + " and " + maximum + ")");
            }
            return new Bounds(minimum, maximum);
        }

        int clamp(int distance) {
            return Math.max(minimum, Math.min(distance, maximum));
        }
    }

    enum Adjustment {
        INCREASE(1),
        DECREASE(-1),
        STAY(0);

        private final int delta;

        Adjustment(int delta) {
            this.delta = delta;
        }

        int delta() {
            return delta;
        }
    }

    static final class AdjustmentPolicy {
        private final Map<UUID, Integer> lowMsptCheckCounts = new HashMap<>();

        Adjustment choose(UUID worldId, double mspt) {
            if (!Double.isFinite(mspt)) {
                lowMsptCheckCounts.remove(worldId);
                return Adjustment.STAY;
            }
            if (mspt >= DECREASE_MSPT_THRESHOLD) {
                lowMsptCheckCounts.remove(worldId);
                return Adjustment.DECREASE;
            }
            if (mspt <= INCREASE_MSPT_THRESHOLD) {
                int passedChecks = Math.min(
                        PASSED_CHECKS_FOR_INCREASE,
                        lowMsptCheckCounts.getOrDefault(worldId, 0) + 1);
                lowMsptCheckCounts.put(worldId, passedChecks);
                return passedChecks >= PASSED_CHECKS_FOR_INCREASE
                        ? Adjustment.INCREASE
                        : Adjustment.STAY;
            }

            lowMsptCheckCounts.remove(worldId);
            return Adjustment.STAY;
        }

        void forget(UUID worldId) {
            lowMsptCheckCounts.remove(worldId);
        }

        void clear() {
            lowMsptCheckCounts.clear();
        }
    }

    static final class TickDurationWindow {
        private final int capacity;
        private final ArrayDeque<Double> durations;

        TickDurationWindow(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Tick duration capacity must be positive");
            }
            this.capacity = capacity;
            this.durations = new ArrayDeque<>(capacity);
        }

        void add(double duration) {
            if (!Double.isFinite(duration) || duration < 0.0) {
                return;
            }
            if (durations.size() == capacity) {
                durations.removeFirst();
            }
            durations.addLast(duration);
        }

        double median(double emptyValue) {
            if (durations.isEmpty()) {
                return emptyValue;
            }
            double[] sorted = durations.stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(sorted);
            int middle = sorted.length / 2;
            if (sorted.length % 2 == 0) {
                return (sorted[middle - 1] + sorted[middle]) / 2.0;
            }
            return sorted[middle];
        }

        void clear() {
            durations.clear();
        }
    }
}
