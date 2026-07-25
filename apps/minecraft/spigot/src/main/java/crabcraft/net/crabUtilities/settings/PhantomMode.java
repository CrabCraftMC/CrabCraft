package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabMessages;

import java.util.Locale;

/**
 * A player's per-player phantom preference.
 *
 * <ul>
 *   <li>{@link #ON} — vanilla phantoms: they spawn and attack normally.</li>
 *   <li>{@link #OFF} — no phantoms: none spawn near the player and none may
 *       attack them. This is the default.</li>
 *   <li>{@link #SAFE} — phantoms may still spawn around the player (the world
 *       stays normal and their {@code time_since_rest} keeps accruing, so the
 *       Night Owl award still works), but no phantom may attack them.</li>
 * </ul>
 *
 * <p>Suppression is purely event-based; no statistic is ever modified.
 */
public enum PhantomMode {
    ON,
    OFF,
    SAFE;

    /** True if natural phantom spawns should be prevented for this player. */
    public boolean suppressesSpawn() {
        return this == OFF;
    }

    /** True if phantoms should be prevented from targeting/damaging this player. */
    public boolean suppressesAttack() {
        return this == OFF || this == SAFE;
    }

    /** Lowercase identifier used in storage, commands and the dialog. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Short, coloured player-facing label as a MiniMessage string, using the
     * shared CrabCraft palette (green = on, gold = safe, red = off). Shown both
     * in the settings dialog options and in chat feedback so they stay in sync.
     */
    public String coloredLabel() {
        return switch (this) {
            case ON -> CrabMessages.SUCCESS_TAG + "On";
            case SAFE -> CrabMessages.HIGHLIGHT_TAG + "Don't attack";
            case OFF -> CrabMessages.ERROR_TAG + "Off";
        };
    }

    /**
     * Parses a mode id (case-insensitive), accepting a few friendly aliases.
     * Anything unknown, null, or empty falls back to {@link #OFF}, the safe
     * default.
     */
    public static PhantomMode fromId(String id) {
        if (id == null) {
            return OFF;
        }
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true" -> ON;
            case "safe" -> SAFE;
            default -> OFF; // "off", "disable", "false", unknown
        };
    }
}
