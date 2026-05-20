package com.jmatt.appleskinspigot;

import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Static holder used by the AppleSkin listeners/sync tasks to reach the owning plugin.
 *
 * <p>The upstream AppleSkinSpigot project ships this class as a standalone {@link JavaPlugin}.
 * In CrabUtilities it's been trimmed to a pure static holder so the AppleSkin logic can be
 * folded into the larger CrabUtilities plugin; {@link #init(JavaPlugin)} is called from
 * {@code crabcraft.net.crabUtilities.appleskin.AppleSkinIntegration#enable}.
 */
public final class AppleSkinSpigot {

    public static final String SATURATION_KEY = "appleskin:saturation";
    public static final String EXHAUSTION_KEY = "appleskin:exhaustion";
    public static final String NATURAL_REGENERATION_KEY = "appleskin:natural_regeneration";

    private static @Nullable JavaPlugin instance;

    private AppleSkinSpigot() {
    }

    public static void init(JavaPlugin plugin) {
        instance = plugin;
    }

    public static JavaPlugin getInstance() {
        return Objects.requireNonNull(instance, "AppleSkinSpigot not initialised yet");
    }

    /**
     * AppleSkin's listeners gate a few Paper-specific code paths on this check. CrabUtilities
     * always runs on Paper, so we short-circuit to {@code true}.
     */
    public static boolean isPaper() {
        return true;
    }
}
