package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabMessages
import java.util.Locale

/** Per-player phantom preference. Suppression is event based and never changes statistics. */
enum class PhantomMode {
    ON, OFF, SAFE;

    fun suppressesSpawn(): Boolean = this == OFF
    fun suppressesAttack(): Boolean = this == OFF || this == SAFE
    fun id(): String = name.lowercase(Locale.ROOT)

    /** Shared coloured label for the settings dialog and chat feedback. */
    fun coloredLabel(): String = when (this) {
        ON -> CrabMessages.SUCCESS_TAG + "On"
        SAFE -> CrabMessages.HIGHLIGHT_TAG + "Don't attack"
        OFF -> CrabMessages.ERROR_TAG + "Off"
    }

    companion object {
        @JvmStatic
        fun fromId(id: String?): PhantomMode = when (id?.lowercase(Locale.ROOT)) {
            "on", "enable", "enabled", "true" -> ON
            "safe" -> SAFE
            else -> OFF
        }
    }
}
