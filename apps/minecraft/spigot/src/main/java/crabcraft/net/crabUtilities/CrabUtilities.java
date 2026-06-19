package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.appleskin.AppleSkinIntegration;
import crabcraft.net.crabUtilities.chat.GlobalChatListener;
import crabcraft.net.crabUtilities.chat.GlobalChatService;
import crabcraft.net.crabUtilities.jade.JadeBootstrap;
import crabcraft.net.crabUtilities.xaero.XaeroBootstrap;
import crabcraft.net.crabUtilities.update.UpdateCommand;
import crabcraft.net.crabUtilities.update.UpdateService;
import crabcraft.net.crabUtilities.voicechat.CrabVoicechatPlugin;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class CrabUtilities extends JavaPlugin {

    private Plugin essentials; // Optional: present when EssentialsX is installed
    private StatsPushTask statsPushTask;
    private UpdateService updateService;
    private CrabVoicechatPlugin voicechatPlugin;
    private LoginStreakCache loginStreakCache;
    private LoginStreakExpansion loginStreakExpansion;
    private GlobalChatService globalChatService;

    @Override
    public void onEnable() {
        // Detect EssentialsX (optional) and register event listeners
        this.essentials = Bukkit.getPluginManager().getPlugin("Essentials");

        // Config: write the bundled default on first run, then top up any keys
        // added in newer versions. Bukkit's YamlConfiguration round-trips
        // comments on modern Paper (parseComments defaults to true), so this
        // keeps the comments shipped in config.yml intact — and restores any an
        // earlier build may have stripped.
        saveDefaultConfig();
        mergeConfigDefaults();
        reloadConfig();

        // Plugin messaging channels (bidirectional for nickname sync)
        getServer().getMessenger().registerOutgoingPluginChannel(this, "crabutilities:nicknames");
        NicknameSync nicknameSync = new NicknameSync(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, "crabutilities:nicknames", nicknameSync);

        // SVC plugin-message channels — required to inject fake PlayerStatePackets
        // for cross-server group GUI roster.
        getServer().getMessenger().registerOutgoingPluginChannel(this, "voicechat:state");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "voicechat:remove_state");

        // Jade server-side companion (block/entity tooltip data for the Jade client mod).
        JadeBootstrap.enable(this);

        // AppleSkin server-side companion (saturation/exhaustion sync).
        AppleSkinIntegration.enable(this);

        // Xaero map server-side companion (sends a world id so Xaero's Minimap/
        // World Map store saved maps per-world rather than per connection IP).
        XaeroBootstrap.enable(this);

        // Event listeners
        Bukkit.getPluginManager().registerEvents(new NicknameMessageListener(this), this);
        Bukkit.getPluginManager().registerEvents(nicknameSync, this);
        nicknameSync.syncAll();

        // Auto-updater
        this.updateService = new UpdateService(this);
        UpdateCommand updateCommand = new UpdateCommand(this, updateService);
        if (getConfig().getBoolean("auto-update.enabled", true)) {
            updateService.start();
        }

        // Commands
        ReloadCommand reloadCommand = new ReloadCommand(this, updateCommand);
        getCommand("crabutilities").setExecutor(reloadCommand);
        getCommand("crabutilities").setTabCompleter(reloadCommand);

        this.statsPushTask = new StatsPushTask(this);
        statsPushTask.start();

        // Login streaks: read-only mirror of the Velocity-owned data,
        // populated via Redis. Soft dependency on PlaceholderAPI — if
        // it's not loaded, the cache still runs (other features could
        // consume it) but the placeholder expansion is skipped.
        this.loginStreakCache = new LoginStreakCache(this);
        loginStreakCache.start();
        Bukkit.getPluginManager().registerEvents(loginStreakCache, this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                this.loginStreakExpansion = new LoginStreakExpansion(this, loginStreakCache);
                if (loginStreakExpansion.register()) {
                    getLogger().info("Registered PlaceholderAPI expansion 'crabutilities'");
                } else {
                    getLogger().warning("PlaceholderAPI expansion registration returned false");
                }
            } catch (NoClassDefFoundError e) {
                getLogger().warning("PlaceholderAPI present but classes not visible: " + e.getMessage());
            }
        } else {
            getLogger().info("PlaceholderAPI not detected — streak placeholders disabled.");
        }

        // Global chat: on servers where it's enabled, capture/format/sync
        // normal chat across the network over Redis. Disabled servers stay
        // fully local.
        this.globalChatService = new GlobalChatService(this);
        globalChatService.start();
        Bukkit.getPluginManager().registerEvents(
                new GlobalChatListener(globalChatService), this);

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

    /**
     * Tops up the live config from the bundled default: adds any keys it is
     * missing (e.g. options introduced by a newer version) and restores
     * block/inline comments that an older build may have stripped. Existing
     * values are never overwritten. Bukkit's YamlConfiguration preserves
     * comments on save (parseComments defaults to true on modern Paper), so the
     * file stays documented across upgrades.
     */
    private void mergeConfigDefaults() {
        FileConfiguration config = getConfig();
        // JavaPlugin loads the bundled config.yml (comments and all) as the
        // default Configuration; reuse it as the source of truth.
        Configuration defaults = config.getDefaults();
        if (defaults == null) {
            return;
        }
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (!config.contains(key, true)) {
                if (defaults.isConfigurationSection(key)) {
                    config.createSection(key);
                } else {
                    config.set(key, defaults.get(key));
                }
                changed = true;
            }
            if (config.getComments(key).isEmpty() && !defaults.getComments(key).isEmpty()) {
                config.setComments(key, defaults.getComments(key));
                changed = true;
            }
            if (config.getInlineComments(key).isEmpty() && !defaults.getInlineComments(key).isEmpty()) {
                config.setInlineComments(key, defaults.getInlineComments(key));
                changed = true;
            }
        }
        if (changed) {
            saveConfig();
        }
    }

    public Plugin getEssentials() {
        return essentials;
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
        if (loginStreakExpansion != null) {
            try { loginStreakExpansion.unregister(); } catch (Throwable ignored) {}
        }
        if (loginStreakCache != null) {
            loginStreakCache.shutdown();
        }
        if (globalChatService != null) {
            globalChatService.shutdown();
        }
    }
}
