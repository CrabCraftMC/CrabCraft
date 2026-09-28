package crabcraft.net.crabUtilities.settings

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Immutable preferences shared with the Velocity proxy, which owns the canonical copy. */
class PlayerSettings(
    phantomMode: PhantomMode?,
    private val mentionPings: Boolean,
    private val acceptMessages: Boolean,
    private val locatorBar: Boolean,
    private val bingoMessages: Boolean,
    private val coordinateHud: Boolean,
) {
    private val phantomMode = phantomMode ?: DEFAULT_PHANTOM_MODE

    fun getPhantomMode() = phantomMode

    fun isMentionPings() = mentionPings

    fun isAcceptMessages() = acceptMessages

    fun isLocatorBar() = locatorBar

    fun isBingoMessages() = bingoMessages

    fun isCoordinateHud() = coordinateHud

    fun withPhantomMode(mode: PhantomMode?) =
        PlayerSettings(mode, mentionPings, acceptMessages, locatorBar, bingoMessages, coordinateHud)

    fun withMentionPings(value: Boolean) =
        PlayerSettings(phantomMode, value, acceptMessages, locatorBar, bingoMessages, coordinateHud)

    fun withAcceptMessages(value: Boolean) =
        PlayerSettings(phantomMode, mentionPings, value, locatorBar, bingoMessages, coordinateHud)

    fun withLocatorBar(value: Boolean) =
        PlayerSettings(phantomMode, mentionPings, acceptMessages, value, bingoMessages, coordinateHud)

    fun withBingoMessages(value: Boolean) =
        PlayerSettings(phantomMode, mentionPings, acceptMessages, locatorBar, value, coordinateHud)

    fun withCoordinateHud(value: Boolean) =
        PlayerSettings(phantomMode, mentionPings, acceptMessages, locatorBar, bingoMessages, value)

    fun toJson() =
        JsonObject().apply {
            addProperty("phantoms", phantomMode.id())
            addProperty("mentionPings", mentionPings)
            addProperty("acceptMessages", acceptMessages)
            addProperty("locatorBar", locatorBar)
            addProperty("bingoMessages", bingoMessages)
            addProperty("coordinateHud", coordinateHud)
        }

    companion object {
        @JvmField val DEFAULT_PHANTOM_MODE = PhantomMode.OFF
        const val DEFAULT_MENTION_PINGS = true
        const val DEFAULT_ACCEPT_MESSAGES = true
        const val DEFAULT_LOCATOR_BAR = false
        const val DEFAULT_BINGO_MESSAGES = true
        const val DEFAULT_COORDINATE_HUD = false
        @JvmField
        val DEFAULTS =
            PlayerSettings(
                DEFAULT_PHANTOM_MODE,
                DEFAULT_MENTION_PINGS,
                DEFAULT_ACCEPT_MESSAGES,
                DEFAULT_LOCATOR_BAR,
                DEFAULT_BINGO_MESSAGES,
                DEFAULT_COORDINATE_HUD,
            )

        /** Missing fields default, including legacy boolean phantom preferences. */
        @JvmStatic
        fun fromJson(obj: JsonObject?): PlayerSettings =
            if (obj == null) DEFAULTS
            else
                PlayerSettings(
                    parsePhantomMode(obj.get("phantoms")),
                    parseBool(obj.get("mentionPings"), DEFAULT_MENTION_PINGS),
                    parseBool(obj.get("acceptMessages"), DEFAULT_ACCEPT_MESSAGES),
                    parseBool(obj.get("locatorBar"), DEFAULT_LOCATOR_BAR),
                    parseBool(obj.get("bingoMessages"), DEFAULT_BINGO_MESSAGES),
                    parseBool(obj.get("coordinateHud"), DEFAULT_COORDINATE_HUD),
                )

        private fun parsePhantomMode(element: JsonElement?): PhantomMode {
            if (element == null || !element.isJsonPrimitive) return DEFAULT_PHANTOM_MODE
            val primitive = element.asJsonPrimitive
            return if (primitive.isBoolean) {
                if (primitive.asBoolean) PhantomMode.ON else PhantomMode.OFF
            } else PhantomMode.fromId(primitive.asString)
        }

        private fun parseBool(element: JsonElement?, fallback: Boolean): Boolean {
            if (element == null || !element.isJsonPrimitive) return fallback
            val primitive = element.asJsonPrimitive
            return if (primitive.isBoolean) primitive.asBoolean else fallback
        }
    }
}
