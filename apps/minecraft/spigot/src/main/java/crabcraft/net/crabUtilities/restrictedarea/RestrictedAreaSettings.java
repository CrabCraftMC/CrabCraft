package crabcraft.net.crabUtilities.restrictedarea;

import org.bukkit.configuration.Configuration;

record RestrictedAreaSettings(boolean enabled, String permission) {

    static final String CONFIG_ROOT = "restricted-area";
    static final String DEFAULT_PERMISSION = "crabutilities.restricted-area.bypass";

    static RestrictedAreaSettings load(final Configuration config) {
        if (!config.getBoolean(CONFIG_ROOT + ".enabled", false)) {
            return disabled();
        }
        final String permission = config.getString(
                CONFIG_ROOT + ".bypass-permission", DEFAULT_PERMISSION).trim();
        if (permission.isEmpty()) {
            throw new IllegalArgumentException("bypass-permission must not be empty");
        }
        return new RestrictedAreaSettings(true, permission);
    }

    static RestrictedAreaSettings disabled() {
        return new RestrictedAreaSettings(false, DEFAULT_PERMISSION);
    }
}
