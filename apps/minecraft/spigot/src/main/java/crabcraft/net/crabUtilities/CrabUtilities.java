package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.update.UpdateCommand;
import crabcraft.net.crabUtilities.update.UpdateService;
import crabcraft.net.crabUtilities.voicechat.CrabVoicechatPlugin;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class CrabUtilities extends JavaPlugin {

    private Plugin essentials; // Optional: present when EssentialsX is installed
    private ResourcePackManager resourcePackManager;
    private StatsPushTask statsPushTask;
    private UpdateService updateService;
    private CrabVoicechatPlugin voicechatPlugin;

    @Override
    public void onEnable() {
        // Detect EssentialsX (optional) and register event listeners
        this.essentials = Bukkit.getPluginManager().getPlugin("Essentials");

        // Config: save default if missing, then merge new keys from bundled
        // default using Configurate (preserves comments unlike Bukkit's saveConfig)
        saveDefaultConfig();
        try {
            java.nio.file.Path configPath = getDataFolder().toPath().resolve("config.yml");
            var loader = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                    .path(configPath)
                    .nodeStyle(org.spongepowered.configurate.yaml.NodeStyle.BLOCK)
                    .build();
            var root = loader.load();
            try (java.io.InputStream defaultIn = getResource("config.yml")) {
                if (defaultIn != null) {
                    var defaults = org.spongepowered.configurate.yaml.YamlConfigurationLoader.builder()
                            .source(() -> new java.io.BufferedReader(new java.io.InputStreamReader(defaultIn)))
                            .build()
                            .load();
                    root.mergeFrom(defaults);
                    loader.save(root);
                }
            }
        } catch (Exception e) {
            getLogger().warning("Failed to merge config defaults: " + e.getMessage());
        }
        reloadConfig();

        this.resourcePackManager = new ResourcePackManager(this);

        // Plugin messaging channels (bidirectional for nickname sync)
        getServer().getMessenger().registerOutgoingPluginChannel(this, "crabutilities:nicknames");
        NicknameSync nicknameSync = new NicknameSync(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, "crabutilities:nicknames", nicknameSync);

        // SVC plugin-message channels — required to inject fake PlayerStatePackets
        // for cross-server group GUI roster.
        getServer().getMessenger().registerOutgoingPluginChannel(this, "voicechat:state");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "voicechat:remove_state");

        // Event listeners
        Bukkit.getPluginManager().registerEvents(new NicknameMessageListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PackJoinListener(this), this);
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

        // Simple Voice Chat integration: creates persistent open groups with
        // deterministic UUIDs and bridges voice across backends via Redis.
        // Soft dependency — skipped silently if the SVC plugin isn't installed.
        BukkitVoicechatService voicechatService = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (voicechatService != null) {
            this.voicechatPlugin = new CrabVoicechatPlugin(this);
            voicechatService.registerPlugin(voicechatPlugin);
            getLogger().info("Registered Simple Voice Chat plugin");
        }

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
        if (voicechatPlugin != null) {
            voicechatPlugin.shutdown();
            getServer().getServicesManager().unregister(voicechatPlugin);
        }
    }
}
