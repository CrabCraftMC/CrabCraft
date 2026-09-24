package crabcraft.net.crabUtilities.settings;

import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.settings.option.Option;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

import java.util.HashMap;

public class SettingRegistry {
    private final CrabUtilities plugin;
    private final HashMap<String, Option> registeredOptions = new HashMap<>();

    public SettingRegistry(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public Option getOption(String name) {
        return registeredOptions.get(name);
    }

    public void registerOption(Option option) {
        registeredOptions.put(option.getIdentifier(), option);
    }

    public void init() {
        for (Option option : registeredOptions.values()) {
            Bukkit.getPluginManager().registerEvents(option, plugin);
            option.onEnable();
        }
    }

    /**
     * Shutdowns all options in the registry
     */
    public void shutdown() {
        for (Option option : registeredOptions.values()) {
            HandlerList.unregisterAll(option);
            option.onDisable();
        }
        registeredOptions.clear();
    }
}
