package crabcraft.net.crabUtilities.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Immutable snapshot of one player's configurable preferences.
 *
 * <p>The JSON round-trip is forward-compatible (unknown keys are ignored,
 * missing keys fall back to the defaults below) and is the on-the-wire shape
 * shared with the Velocity proxy, which owns the canonical copy in Postgres.
 *
 * <p>Defaults: phantoms {@link PhantomMode#OFF} (no spawn, no attack), mention
 * pings ON, private messages accepted, and locator bar OFF.
 */
public final class PlayerSettings {

    public static final PhantomMode DEFAULT_PHANTOM_MODE = PhantomMode.OFF;
    public static final boolean DEFAULT_MENTION_PINGS = true;
    public static final boolean DEFAULT_ACCEPT_MESSAGES = true;
    public static final boolean DEFAULT_LOCATOR_BAR = false;

    public static final PlayerSettings DEFAULTS =
            new PlayerSettings(DEFAULT_PHANTOM_MODE, DEFAULT_MENTION_PINGS,
                    DEFAULT_ACCEPT_MESSAGES, DEFAULT_LOCATOR_BAR);

    private final PhantomMode phantomMode;
    private final boolean mentionPings;
    private final boolean acceptMessages;
    private final boolean locatorBar;

    public PlayerSettings(PhantomMode phantomMode, boolean mentionPings,
                          boolean acceptMessages, boolean locatorBar) {
        this.phantomMode = phantomMode == null ? DEFAULT_PHANTOM_MODE : phantomMode;
        this.mentionPings = mentionPings;
        this.acceptMessages = acceptMessages;
        this.locatorBar = locatorBar;
    }

    public PhantomMode getPhantomMode() {
        return phantomMode;
    }

    public boolean isMentionPings() {
        return mentionPings;
    }

    public boolean isAcceptMessages() {
        return acceptMessages;
    }

    public boolean isLocatorBar() {
        return locatorBar;
    }

    public PlayerSettings withPhantomMode(PhantomMode mode) {
        return new PlayerSettings(mode, mentionPings, acceptMessages, locatorBar);
    }

    public PlayerSettings withMentionPings(boolean value) {
        return new PlayerSettings(phantomMode, value, acceptMessages, locatorBar);
    }

    public PlayerSettings withAcceptMessages(boolean value) {
        return new PlayerSettings(phantomMode, mentionPings, value, locatorBar);
    }

    public PlayerSettings withLocatorBar(boolean value) {
        return new PlayerSettings(phantomMode, mentionPings, acceptMessages, value);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("phantoms", phantomMode.id());
        obj.addProperty("mentionPings", mentionPings);
        obj.addProperty("acceptMessages", acceptMessages);
        obj.addProperty("locatorBar", locatorBar);
        return obj;
    }

    /**
     * Parses a stored settings object, defaulting any absent field. Tolerant of
     * the legacy phantom boolean format ({@code {"phantoms": true|false}}) as
     * well as the current string-mode format. Never throws on missing keys.
     */
    public static PlayerSettings fromJson(JsonObject obj) {
        if (obj == null) {
            return DEFAULTS;
        }
        return new PlayerSettings(
                parsePhantomMode(obj.get("phantoms")),
                parseBool(obj.get("mentionPings"), DEFAULT_MENTION_PINGS),
                parseBool(obj.get("acceptMessages"), DEFAULT_ACCEPT_MESSAGES),
                parseBool(obj.get("locatorBar"), DEFAULT_LOCATOR_BAR));
    }

    private static PhantomMode parsePhantomMode(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return DEFAULT_PHANTOM_MODE;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean() ? PhantomMode.ON : PhantomMode.OFF;
        }
        return PhantomMode.fromId(primitive.getAsString());
    }

    private static boolean parseBool(JsonElement element, boolean fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isBoolean() ? primitive.getAsBoolean() : fallback;
    }
}
