package crabcraft.net.crabUtilities;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class CrabUtilities extends JavaPlugin {

    private Plugin essentials; // Optional: present when EssentialsX is installed
    private ResourcePackManager resourcePackManager;

    @Override
    public void onEnable() {
        // Detect EssentialsX (optional) and register event listeners
        this.essentials = Bukkit.getPluginManager().getPlugin("Essentials");

        // Config and managers
        saveDefaultConfig();
        this.resourcePackManager = new ResourcePackManager(this);

        // Event listeners
        Bukkit.getPluginManager().registerEvents(new NicknameMessageListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PackJoinListener(this), this);

        // Commands
        PackCommand packCommand = new PackCommand(this);
        getCommand("pack").setExecutor(packCommand);
        getCommand("pack").setTabCompleter(packCommand);

        getLogger().info("CrabUtilities enabled. EssentialsX present: " + (essentials != null));
    }

    public Plugin getEssentials() {
        return essentials;
    }

    public ResourcePackManager getResourcePackManager() {
        return resourcePackManager;
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
