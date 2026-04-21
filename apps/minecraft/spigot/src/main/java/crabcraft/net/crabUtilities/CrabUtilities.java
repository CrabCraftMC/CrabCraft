package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.update.UpdateCommand;
import crabcraft.net.crabUtilities.update.UpdateService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class CrabUtilities extends JavaPlugin {

    private Plugin essentials; // Optional: present when EssentialsX is installed
    private ResourcePackManager resourcePackManager;
    private StatsPushTask statsPushTask;
    private UpdateService updateService;

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

        // Auto-updater
        this.updateService = new UpdateService(this);
        UpdateCommand updateCommand = new UpdateCommand(this, updateService);
        if (getConfig().getBoolean("auto-update.enabled", true)) {
            updateService.start();
        }

        // Commands
        PackCommand packCommand = new PackCommand(this);
        getCommand("pack").setExecutor(packCommand);
        getCommand("pack").setTabCompleter(packCommand);

        ReloadCommand reloadCommand = new ReloadCommand(this, updateCommand);
        getCommand("crabutilities").setExecutor(reloadCommand);
        getCommand("crabutilities").setTabCompleter(reloadCommand);

        this.statsPushTask = new StatsPushTask(this);
        statsPushTask.start();

        getLogger().info("CrabUtilities enabled. EssentialsX present: " + (essentials != null));
    }

    public Plugin getEssentials() {
        return essentials;
    }

    public ResourcePackManager getResourcePackManager() {
        return resourcePackManager;
    }

    /**
     * Exposes the on-disk plugin jar so the updater can stage a replacement with
     * the same filename into {@code plugins/update/}.
     */
    public File getPluginJarFile() {
        return getFile();
    }

    @Override
    public void onDisable() {
        if (statsPushTask != null) {
            statsPushTask.shutdown();
        }
        if (updateService != null) {
            updateService.shutdown();
        }
    }
}
