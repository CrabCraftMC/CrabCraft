package crabcraft.net.crabUtilities.settings

import crabcraft.net.crabUtilities.CrabMessages
import java.util.Locale

/** Event-based suppression never modifies the time-since-rest statistic. */
enum class PhantomMode {
    ON,
    OFF,
    SAFE;

    fun suppressesSpawn() = this == OFF

    fun suppressesAttack() = this == OFF || this == SAFE

    fun id() = name.lowercase(Locale.ROOT)

    fun coloredLabel() =
        when (this) {
            ON -> CrabMessages.SUCCESS_TAG + "On"
            SAFE -> CrabMessages.HIGHLIGHT_TAG + "Don't attack"
            OFF -> CrabMessages.ERROR_TAG + "Off"
        }

    companion object {
        /** Unknown, null and empty identifiers fall back to OFF. */
        @JvmStatic
        fun fromId(id: String?): PhantomMode =
            when (id?.lowercase(Locale.ROOT)) {
                "on",
                "enable",
                "enabled",
                "true" -> ON
                "safe" -> SAFE
                else -> OFF
            }
    }
}
