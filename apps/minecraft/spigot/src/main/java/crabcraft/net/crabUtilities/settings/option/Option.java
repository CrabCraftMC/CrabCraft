package crabcraft.net.crabUtilities.settings.option;

import org.bukkit.event.Listener;

/**
 * Interface that is used to create new options.
 * Implements Bukkit's {@link Listener} for easier registration
 */
public interface Option extends Listener {
    String getIdentifier();

    default void onEnable() {}
    default void onDisable() {}
}
