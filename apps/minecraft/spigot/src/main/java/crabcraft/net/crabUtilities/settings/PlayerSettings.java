package crabcraft.net.crabUtilities.settings;

import com.google.gson.JsonObject;

/**
 * Immutable snapshot of one player's configurable preferences.
 *
 * <p>Currently the only setting is whether phantoms may spawn for the
 * player. More toggles can be added here as the {@code /settings} menu
 * grows; the JSON round-trip is forward-compatible (unknown keys are
 * ignored, missing keys fall back to the defaults below).
 *
 * <p><b>Phantoms default OFF.</b> A brand-new player — or any player whose
 * record is missing or unreadable — is treated as having phantoms disabled.
 */
public final class PlayerSettings {

    /** Server-wide defaults applied when a player has no stored record. */
    public static final boolean DEFAULT_PHANTOMS_ENABLED = false;

    public static final PlayerSettings DEFAULTS = new PlayerSettings(DEFAULT_PHANTOMS_ENABLED);

    private final boolean phantomsEnabled;

    public PlayerSettings(boolean phantomsEnabled) {
        this.phantomsEnabled = phantomsEnabled;
    }

    public boolean isPhantomsEnabled() {
        return phantomsEnabled;
    }

    /** Returns a copy of this settings object with {@code phantomsEnabled} changed. */
    public PlayerSettings withPhantomsEnabled(boolean enabled) {
        return new PlayerSettings(enabled);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("phantoms", phantomsEnabled);
        return obj;
    }

    /**
     * Parses a stored settings object, defaulting any absent field. Never
     * throws on missing keys; callers should still guard against malformed
     * JSON before calling.
     */
    public static PlayerSettings fromJson(JsonObject obj) {
        boolean phantoms = obj != null && obj.has("phantoms")
                ? obj.get("phantoms").getAsBoolean()
                : DEFAULT_PHANTOMS_ENABLED;
        return new PlayerSettings(phantoms);
    }
}
