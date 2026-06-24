package crabcraft.net.crabUtilities.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Immutable snapshot of one player's configurable preferences.
 *
 * <p>Currently the only setting is the player's {@link PhantomMode}. More
 * settings can be added here as the {@code /settings} dialog grows; the JSON
 * round-trip is forward-compatible (unknown keys are ignored, missing keys
 * fall back to the defaults below).
 *
 * <p><b>Phantoms default {@link PhantomMode#OFF}.</b> A brand-new player — or
 * any player whose record is missing or unreadable — is treated as having
 * phantoms off (no spawn, no attack).
 */
public final class PlayerSettings {

    /** Server-wide default applied when a player has no stored record. */
    public static final PhantomMode DEFAULT_PHANTOM_MODE = PhantomMode.OFF;

    public static final PlayerSettings DEFAULTS = new PlayerSettings(DEFAULT_PHANTOM_MODE);

    private final PhantomMode phantomMode;

    public PlayerSettings(PhantomMode phantomMode) {
        this.phantomMode = phantomMode == null ? DEFAULT_PHANTOM_MODE : phantomMode;
    }

    public PhantomMode getPhantomMode() {
        return phantomMode;
    }

    /** Returns a copy of this settings object with the phantom mode changed. */
    public PlayerSettings withPhantomMode(PhantomMode mode) {
        return new PlayerSettings(mode);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("phantoms", phantomMode.id());
        return obj;
    }

    /**
     * Parses a stored settings object, defaulting any absent field. Tolerant of
     * the legacy boolean format ({@code {"phantoms": true|false}}, where
     * {@code true} meant ON and {@code false} meant OFF) as well as the current
     * string-mode format ({@code {"phantoms": "on"|"off"|"safe"}}). Never throws
     * on missing keys.
     */
    public static PlayerSettings fromJson(JsonObject obj) {
        if (obj == null || !obj.has("phantoms")) {
            return new PlayerSettings(DEFAULT_PHANTOM_MODE);
        }
        JsonElement element = obj.get("phantoms");
        PhantomMode mode = DEFAULT_PHANTOM_MODE;
        if (element != null && element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                mode = primitive.getAsBoolean() ? PhantomMode.ON : PhantomMode.OFF;
            } else {
                mode = PhantomMode.fromId(primitive.getAsString());
            }
        }
        return new PlayerSettings(mode);
    }
}
