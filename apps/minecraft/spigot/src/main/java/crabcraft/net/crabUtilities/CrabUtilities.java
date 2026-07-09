package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.appleskin.AppleSkinIntegration;
import crabcraft.net.crabUtilities.bluemap.SignMarkerService;
import crabcraft.net.crabUtilities.cauldron.CauldronRecipeListener;
import crabcraft.net.crabUtilities.chat.GlobalChatListener;
import crabcraft.net.crabUtilities.chat.GlobalChatService;
import crabcraft.net.crabUtilities.chat.MentionAutocompleteListener;
import crabcraft.net.crabUtilities.enderman.EndermanGriefListener;
import crabcraft.net.crabUtilities.happyghast.HappyGhastSpeedManager;
import crabcraft.net.crabUtilities.heads.PersistentHeadsListener;
import crabcraft.net.crabUtilities.heads.PlayerHeadDropsListener;
import crabcraft.net.crabUtilities.jade.JadeBootstrap;
import crabcraft.net.crabUtilities.netherportals.CustomNetherPortalListener;
import crabcraft.net.crabUtilities.recipes.UnlockAllRecipesManager;
import crabcraft.net.crabUtilities.shulker.ShulkerShellListener;
import crabcraft.net.crabUtilities.settings.LocatorBarManager;
import crabcraft.net.crabUtilities.settings.PhantomManager;
import crabcraft.net.crabUtilities.settings.PlayerSettingsService;
import crabcraft.net.crabUtilities.settings.SettingsCommand;
import crabcraft.net.crabUtilities.settings.SettingsDialog;
import crabcraft.net.crabUtilities.sleep.SleepBroadcastListener;
import crabcraft.net.crabUtilities.xaero.XaeroBootstrap;
import crabcraft.net.crabUtilities.update.UpdateCommand;
import crabcraft.net.crabUtilities.update.UpdateService;
import crabcraft.net.crabUtilities.voicechat.CrabVoicechatPlugin;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class CrabUtilities extends JavaPlugin {

    private Plugin essentials; // Optional: present when EssentialsX is installed
    private StatsPushTask statsPushTask;
    private UpdateService updateService;
    private CrabVoicechatPlugin voicechatPlugin;
    private LoginStreakCache loginStreakCache;
    private LoginStreakExpansion loginStreakExpansion;
    private GlobalChatService globalChatService;
    private GlobalChatListener globalChatListener;
    private MentionAutocompleteListener mentionAutocompleteListener;
    private PlayerSettingsService playerSettingsService;
    private PhantomManager phantomManager;
    private LocatorBarManager locatorBarManager;
    private SettingsDialog settingsDialog;
    private SignMarkerService signMarkerService;
    private HappyGhastSpeedManager happyGhastSpeedManager;
    private UnlockAllRecipesManager unlockAllRecipesManager;
    private CustomNetherPortalListener customNetherPortalListener;
    private NicknameSync nicknameSync;

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
        this.nicknameSync = new NicknameSync(this);
        Bukkit.getPluginManager().registerEvents(nicknameSync, this);
        nicknameSync.start();
        this.mentionAutocompleteListener = new MentionAutocompleteListener(this);
        Bukkit.getPluginManager().registerEvents(mentionAutocompleteListener, this);
        mentionAutocompleteListener.refreshAll();

        // Sleep broadcast: announce who slept when the night is skipped. Opt-in
        // and disabled by default; the listener reads config live, so
        // /crabutilities reload toggles it without re-registration.
        Bukkit.getPluginManager().registerEvents(new SleepBroadcastListener(this), this);

        // Custom nether portals: allow non-rectangular / custom-size portals.
        // Opt-in and disabled by default; the enabled flag is read live so
        // /crabutilities reload toggles it without re-registration. The parsed
        // frame-materials/size settings are cached and invalidated on reload.
        this.customNetherPortalListener = new CustomNetherPortalListener(this);
        Bukkit.getPluginManager().registerEvents(customNetherPortalListener, this);

        // Small opt-in survival tweaks (ported from VanillaTweaks/PaperTweaks).
        // Each reads config live and is disabled by default, so /crabutilities
        // reload toggles them without re-registration:
        //   - Cauldron crafting (concrete powder -> concrete, dirt -> mud)
        //   - Enderman-only grief prevention (targeted, unlike the global game rule)
        //   - Predictable shulker shell drops (more on a player kill)
        //   - Player heads dropping on player kills
        //   - Player heads keeping their name/lore when broken and replaced
        Bukkit.getPluginManager().registerEvents(new CauldronRecipeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EndermanGriefListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ShulkerShellListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerHeadDropsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PersistentHeadsListener(this), this);

        // Unlock-all-recipes: unlock every recipe on join (and for anyone
        // already online). Caches the recipe key set; the cache is rebuilt and
        // re-applied on /crabutilities reload.
        this.unlockAllRecipesManager = new UnlockAllRecipesManager(this);
        Bukkit.getPluginManager().registerEvents(unlockAllRecipesManager, this);
        unlockAllRecipesManager.start();

        // Auto-updater
        this.updateService = new UpdateService(this);
        UpdateCommand updateCommand = new UpdateCommand(this, updateService);
        startUpdateService();

        // Commands
        ReloadCommand reloadCommand = new ReloadCommand(this, updateCommand);
        getCommand("crabutilities").setExecutor(reloadCommand);
        getCommand("crabutilities").setTabCompleter(reloadCommand);

        startStatsPushTask();

        // Login streaks: read-only mirror of the Velocity-owned data,
        // populated via Redis. Soft dependency on PlaceholderAPI — if
        // it's not loaded, the cache still runs (other features could
        // consume it) but the placeholder expansion is skipped.
        startLoginStreakCache();

        // Global chat: on servers where it's enabled, capture/format/sync
        // normal chat across the network over Redis. Disabled servers stay
        // fully local.
        startGlobalChatService();

        // Per-player settings (/settings) and the gameplay toggles they drive.
        // Settings are stored network-wide in Redis; phantoms and locator bar default OFF.
        startPlayerSettings();

        // BlueMap sign markers: signs with [map] on the top line become POI
        // markers on the BlueMap web map. Soft dependency — skipped when the
        // BlueMap plugin isn't installed or the feature is off in config.
        startSignMarkers();

        // Happy ghast ridden speed boost: while a player is riding, a transient
        // flying_speed modifier makes the ghast faster. Disabled by default.
        startHappyGhastSpeed();

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

    public void refreshMentionAutocomplete() {
        if (mentionAutocompleteListener != null) {
            mentionAutocompleteListener.refreshAll();
        }
    }

    /** The per-player settings mirror, or {@code null} before it has started. */
    public PlayerSettingsService getPlayerSettingsService() {
        return playerSettingsService;
    }

    /**
     * Exposes the on-disk plugin jar so the updater can stage a replacement with
     * the same filename into {@code plugins/update/}.
     */
    public File getPluginJarFile() {
        return getFile();
    }

    public List<String> reloadRuntimeConfig() {
        reloadConfig();
        mergeConfigDefaults();
        reloadConfig();

        List<String> messages = new ArrayList<>();

        stopStatsPushTask();
        startStatsPushTask();
        messages.add("Stats push restarted with current season, Redis, and interval settings.");

        stopLoginStreakCache();
        startLoginStreakCache();
        messages.add("Login streak cache restarted with current Redis settings.");

        if (nicknameSync != null) {
            nicknameSync.shutdown();
            nicknameSync.start();
            messages.add("Nickname Redis sync restarted with current Redis settings.");
        }

        stopGlobalChatService();
        startGlobalChatService();
        refreshMentionAutocomplete();
        messages.add("Global chat restarted with current Redis, format, and mention settings.");

        stopPlayerSettings();
        startPlayerSettings();
        messages.add("Player settings, phantom manager, and locator bar manager restarted.");

        stopSignMarkers();
        startSignMarkers();
        messages.add(signMarkerService != null
                ? "BlueMap sign markers restarted with current settings."
                : "BlueMap sign markers inactive (disabled in config or BlueMap not installed).");

        stopHappyGhastSpeed();
        startHappyGhastSpeed();
        messages.add(happyGhastSpeedManager != null
                ? "Happy ghast ridden speed boost active (x" + happyGhastSpeedManager.getMultiplier() + ")."
                : "Happy ghast ridden speed boost inactive (disabled in config).");

        if (unlockAllRecipesManager != null) {
            unlockAllRecipesManager.refresh();
            messages.add("Recipe unlock cache rebuilt and re-applied to online players (if enabled).");
        }

        if (customNetherPortalListener != null) {
            customNetherPortalListener.invalidate();
            messages.add("Custom nether portal settings cache cleared (re-read on next ignite).");
        }

        if (updateService != null) {
            updateService.shutdown();
            if (getConfig().getBoolean("auto-update.enabled", true)) {
                updateService.start();
                messages.add("Auto-update scheduler restarted with current settings.");
            } else {
                messages.add("Auto-update scheduler stopped because auto-update.enabled=false.");
            }
        }

        messages.add("Restart required for voicechat.cross-server and mod-protocols settings.");
        return messages;
    }

    private void startUpdateService() {
        if (updateService != null && getConfig().getBoolean("auto-update.enabled", true)) {
            updateService.start();
        }
    }

    private void startStatsPushTask() {
        this.statsPushTask = new StatsPushTask(this);
        statsPushTask.start();
    }

    private void stopStatsPushTask() {
        if (statsPushTask != null) {
            statsPushTask.shutdown();
            statsPushTask = null;
        }
    }

    private void startLoginStreakCache() {
        this.loginStreakCache = new LoginStreakCache(this);
        loginStreakCache.start();
        Bukkit.getPluginManager().registerEvents(loginStreakCache, this);
        registerLoginStreakExpansion();
    }

    private void stopLoginStreakCache() {
        if (loginStreakExpansion != null) {
            try { loginStreakExpansion.unregister(); } catch (Throwable ignored) {}
            loginStreakExpansion = null;
        }
        if (loginStreakCache != null) {
            HandlerList.unregisterAll(loginStreakCache);
            loginStreakCache.shutdown();
            loginStreakCache = null;
        }
    }

    private void registerLoginStreakExpansion() {
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
    }

    private void startGlobalChatService() {
        this.globalChatService = new GlobalChatService(this);
        globalChatService.start();
        this.globalChatListener = new GlobalChatListener(globalChatService);
        Bukkit.getPluginManager().registerEvents(globalChatListener, this);
    }

    private void startPlayerSettings() {
        this.playerSettingsService = new PlayerSettingsService(this);
        this.phantomManager = new PhantomManager(this, playerSettingsService);
        this.locatorBarManager = new LocatorBarManager(this, playerSettingsService);
        this.settingsDialog = new SettingsDialog(playerSettingsService);

        Bukkit.getPluginManager().registerEvents(playerSettingsService, this);
        Bukkit.getPluginManager().registerEvents(phantomManager, this);
        Bukkit.getPluginManager().registerEvents(locatorBarManager, this);

        locatorBarManager.start();
        playerSettingsService.start();
        phantomManager.start();

        SettingsCommand settingsCommand = new SettingsCommand(playerSettingsService, settingsDialog);
        if (getCommand("settings") != null) {
            getCommand("settings").setExecutor(settingsCommand);
            getCommand("settings").setTabCompleter(settingsCommand);
        }
    }

    private void stopPlayerSettings() {
        this.settingsDialog = null;
        if (locatorBarManager != null) {
            HandlerList.unregisterAll(locatorBarManager);
            locatorBarManager = null;
        }
        if (phantomManager != null) {
            HandlerList.unregisterAll(phantomManager);
            phantomManager = null;
        }
        if (playerSettingsService != null) {
            HandlerList.unregisterAll(playerSettingsService);
            playerSettingsService.shutdown();
            playerSettingsService = null;
        }
    }

    private void startSignMarkers() {
        if (!getConfig().getBoolean("bluemap.sign-markers.enabled", false)) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("BlueMap") == null) {
            getLogger().info("BlueMap not detected — sign markers disabled.");
            return;
        }
        // Same pattern as the PlaceholderAPI expansion: SignMarkerService is
        // the only class referencing the BlueMap API, and it is only loaded
        // here, after BlueMap's presence has been confirmed. LinkageError (not
        // just NoClassDefFoundError) because the first BlueMap-class touch is
        // a method-ref bootstrap, whose failure surfaces as BootstrapMethodError.
        try {
            this.signMarkerService = new SignMarkerService(this);
            signMarkerService.start();
            getLogger().info("BlueMap sign markers enabled (keyword: " + signMarkerService.getKeyword() + ")");
        } catch (LinkageError e) {
            this.signMarkerService = null;
            getLogger().warning("BlueMap present but API classes not usable: " + e.getMessage());
        }
    }

    private void stopSignMarkers() {
        if (signMarkerService != null) {
            signMarkerService.shutdown();
            signMarkerService = null;
        }
    }

    private void startHappyGhastSpeed() {
        HappyGhastSpeedManager manager = new HappyGhastSpeedManager(this);
        if (!manager.isEnabled()) {
            return;
        }
        this.happyGhastSpeedManager = manager;
        Bukkit.getPluginManager().registerEvents(manager, this);
        manager.start();
        getLogger().info("Happy ghast ridden speed boost enabled (x" + manager.getMultiplier() + ")");
    }

    private void stopHappyGhastSpeed() {
        if (happyGhastSpeedManager != null) {
            HandlerList.unregisterAll(happyGhastSpeedManager);
            happyGhastSpeedManager.shutdown();
            happyGhastSpeedManager = null;
        }
    }

    private void stopGlobalChatService() {
        if (globalChatListener != null) {
            HandlerList.unregisterAll(globalChatListener);
            globalChatListener = null;
        }
        if (globalChatService != null) {
            globalChatService.shutdown();
            globalChatService = null;
        }
    }

    @Override
    public void onDisable() {
        stopStatsPushTask();
        if (updateService != null) {
            updateService.shutdown();
        }
        if (voicechatPlugin != null) {
            // Never let voice-bridge cleanup abort the rest of onDisable.
            try {
                voicechatPlugin.shutdown();
            } catch (Exception e) {
                getLogger().warning("Voice bridge shutdown failed: " + e.getMessage());
            }
            getServer().getServicesManager().unregister(voicechatPlugin);
        }
        stopLoginStreakCache();
        if (nicknameSync != null) {
            nicknameSync.shutdown();
            nicknameSync = null;
        }
        stopGlobalChatService();
        stopPlayerSettings();
        stopSignMarkers();
        stopHappyGhastSpeed();
    }
}
