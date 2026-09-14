package crabcraft.net.crabUtilities.restrictedarea

import org.bukkit.configuration.Configuration

data class RestrictedAreaSettings(private val enabled: Boolean, private val permission: String) {
    fun enabled(): Boolean = enabled
    fun permission(): String = permission

    companion object {
        const val CONFIG_ROOT = "restricted-area"
        const val DEFAULT_PERMISSION = "crabutilities.restricted-area.bypass"

        @JvmStatic
        fun load(config: Configuration): RestrictedAreaSettings {
            if (!config.getBoolean("$CONFIG_ROOT.enabled", false)) return disabled()
            val permission = config.getString("$CONFIG_ROOT.bypass-permission", DEFAULT_PERMISSION)!!.trim { it <= ' ' }
            require(permission.isNotEmpty()) { "bypass-permission must not be empty" }
            return RestrictedAreaSettings(true, permission)
        }

        @JvmStatic
        fun disabled(): RestrictedAreaSettings = RestrictedAreaSettings(false, DEFAULT_PERMISSION)
    }
}
