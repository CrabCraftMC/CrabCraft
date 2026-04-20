package crabcraft.net.crabUtilities;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class CrabUtilities extends JavaPlugin {

    private Plugin essentials; // Optional: present when EssentialsX is installed
    private ResourcePackManager resourcePackManager;
    private StatsRedisHandler statsRedisHandler;

    @Override
    public void onEnable() {
        // Detect EssentialsX (optional) and register event listeners
        this.essentials = Bukkit.getPluginManager().getPlugin("Essentials");

        // Config and managers
        saveDefaultConfig();
        this.resourcePackManager = new ResourcePackManager(this);

        // Plugin messaging channels
        getServer().getMessenger().registerOutgoingPluginChannel(this, "crabutilities:nicknames");

        // Event listeners
        Bukkit.getPluginManager().registerEvents(new NicknameMessageListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PackJoinListener(this), this);
        NicknameSync nicknameSync = new NicknameSync(this);
        Bukkit.getPluginManager().registerEvents(nicknameSync, this);
        nicknameSync.syncAll();

        // Commands
        PackCommand packCommand = new PackCommand(this);
        getCommand("pack").setExecutor(packCommand);
        getCommand("pack").setTabCompleter(packCommand);

        ReloadCommand reloadCommand = new ReloadCommand(this);
        getCommand("crabutilities").setExecutor(reloadCommand);
        getCommand("crabutilities").setTabCompleter(reloadCommand);

        this.statsRedisHandler = new StatsRedisHandler(this);
        statsRedisHandler.start();

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
        if (statsRedisHandler != null) {
            statsRedisHandler.shutdown();
        }
    }
}
