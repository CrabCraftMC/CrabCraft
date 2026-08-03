package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.accurateplacement.AccurateBlockPlacementManager;
import crabcraft.net.crabUtilities.appleskin.AppleSkinIntegration;
import crabcraft.net.crabUtilities.awards.SuspiciousBrushTracker;
import crabcraft.net.crabUtilities.bluemap.SignMarkerService;
import crabcraft.net.crabUtilities.bingo.BingoManager;
import crabcraft.net.crabUtilities.cauldron.CauldronRecipeListener;
import crabcraft.net.crabUtilities.chat.EssentialsMentionAutocompleteListener;
import crabcraft.net.crabUtilities.chat.GlobalChatListener;
import crabcraft.net.crabUtilities.chat.GlobalChatService;
import crabcraft.net.crabUtilities.chat.MentionAutocompleteListener;
import crabcraft.net.crabUtilities.chat.bridge.PaperChatBridge;
import crabcraft.net.crabUtilities.config.ModuleConfigManager;
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
import crabcraft.net.crabUtilities.slime.SlimeCommand;
import crabcraft.net.crabUtilities.slime.SlimeMapListener;
import crabcraft.net.crabUtilities.sleep.SleepBroadcastListener;
import crabcraft.net.crabUtilities.xaero.XaeroBootstrap;
import crabcraft.net.crabUtilities.xpclumps.ExperienceClumpListener;
import crabcraft.net.crabUtilities.update.UpdateCommand;
import crabcraft.net.crabUtilities.update.UpdateService;
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceCommand;
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceManager;
import crabcraft.net.crabUtilities.villagers.SharedVillagerDiscountListener;
import crabcraft.net.crabUtilities.voicechat.SimpleVoiceAnimationsIntegration;
import crabcraft.net.crabUtilities.voicechat.VoicechatIntegration;
import crabcraft.net.crabUtilities.media.MediaFeature;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CrabUtilities extends JavaPlugin {

    private Plugin essentials; // Optional: present when EssentialsX is installed
    private StatsPushTask statsPushTask;
    private UpdateService updateService;
    private AutoCloseable voicechatRegistration;
    private LoginStreakCache loginStreakCache;
    private LoginStreakExpansion loginStreakExpansion;
    private GlobalChatService globalChatService;
    private GlobalChatListener globalChatListener;
    private PaperChatBridge chatBridge;
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
    private ViewDistanceManager viewDistanceManager;
    private AccurateBlockPlacementManager accurateBlockPlacementManager;
    private SimpleVoiceAnimationsIntegration simpleVoiceAnimationsIntegration;
    private ModuleConfigManager moduleConfigManager;
    private BingoManager bingoManager;

    @Override
    public void onEnable() {
        // Detect EssentialsX (optional) and register event listeners
        this.essentials = Bukkit.getPluginManager().getPlugin("Essentials");

        // Write any missing core/module defaults and expose one merged
        // in-memory view to existing configuration consumers. Legacy values
        // are deliberately not migrated; administrators move them manually.
        saveDefaultConfig();
        this.moduleConfigManager = new ModuleConfigManager(this);
        moduleConfigManager.initialise();

        // Discs, horns and the shared yt-dlp/FFmpeg media pipeline.
        MediaFeature.enable(this);

        // SVC plugin-message channels — required to inject fake PlayerStatePackets
        // for cross-server group GUI roster.
        getServer().getMessenger().registerOutgoingPluginChannel(this, "voicechat:state");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "voicechat:remove_state");

        // Jade server-side companion (block/entity tooltip data for the Jade client mod).
        JadeBootstrap.enable(this);

        // AppleSkin server-side companion (saturation/exhaustion sync).
        AppleSkinIntegration.enable(this);

        // Simple Voice Animations server-side companion (per-player head settings sync).
        startSimpleVoiceAnimations();

        // Xaero map identity (separates backend map storage behind a proxy).
        XaeroBootstrap.enable(this);

        // Carpet accurate-block-placement protocol for Litematica and
        // Tweakeroo EasyPlace clients.
        startAccurateBlockPlacement();

        // Event listeners
        Bukkit.getPluginManager().registerEvents(new NicknameMessageListener(this), this);
        if (essentials != null) {
            this.nicknameSync = new NicknameSync(this);
            Bukkit.getPluginManager().registerEvents(nicknameSync, this);
            nicknameSync.start();
        } else {
            getLogger().info("EssentialsX not detected — nickname Redis sync disabled.");
        }
        this.mentionAutocompleteListener = new MentionAutocompleteListener(this);
        Bukkit.getPluginManager().registerEvents(mentionAutocompleteListener, this);
        if (essentials != null) {
            Bukkit.getPluginManager().registerEvents(
                    new EssentialsMentionAutocompleteListener(this, mentionAutocompleteListener), this);
        }
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
        //   - Nearby experience orbs clumping together when they spawn
        //   - Villager cure and raid discounts shared with nearby players
        Bukkit.getPluginManager().registerEvents(new CauldronRecipeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EndermanGriefListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ShulkerShellListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerHeadDropsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PersistentHeadsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ExperienceClumpListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SharedVillagerDiscountListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SuspiciousBrushTracker(), this);
        Bukkit.getPluginManager().registerEvents(new SlimeMapListener(), this);

        startAccurateBlockPlacement();

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
        ViewDistanceCommand viewDistanceCommand =
                new ViewDistanceCommand(() -> viewDistanceManager);
        ReloadCommand reloadCommand =
                new ReloadCommand(this, updateCommand, viewDistanceCommand);
        getCommand("crabutilities").setExecutor(reloadCommand);
        getCommand("crabutilities").setTabCompleter(reloadCommand);

        SlimeCommand slimeCommand = new SlimeCommand();
        getCommand("slime").setExecutor(slimeCommand);
        getCommand("slime").setTabCompleter(slimeCommand);

        // Private and staff chat enter through Paper so InteractiveChat, emoji
        // plugins and other local processors see them before Velocity routes
        // them across the network.
        this.chatBridge = new PaperChatBridge(this);
        chatBridge.start();

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

        // Weekly bingo is opt-out for players but feature-gated for deployment.
        startBingo();

        // BlueMap sign markers: signs with [map] on the top line become POI
        // markers on the BlueMap web map. Soft dependency — skipped when the
        // BlueMap plugin isn't installed or the feature is off in config.
        startSignMarkers();

        // Happy ghast ridden speed boost: while a player is riding, a transient
        // flying_speed modifier makes the ghast faster. Disabled by default.
        startHappyGhastSpeed();

        // Adapt simulation and view distances to server tick time. Disabled by
        // default; the manager owns its tick listener and repeating task.
        startViewDistanceManager();

        // Simple Voice Chat integration: mirrors group definitions and bridges
        // grouped voice across backends via Redis.
        // Soft dependency — skipped silently if the SVC plugin isn't installed.
        if (Bukkit.getPluginManager().getPlugin("voicechat") != null) {
            try {
                this.voicechatRegistration = VoicechatIntegration.register(this);
            } catch (LinkageError e) {
                getLogger().warning("Simple Voice Chat present but API classes not usable: " + e.getMessage());
            }
        }

        getLogger().info("CrabUtilities enabled. EssentialsX present: " + (essentials != null));
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

    public boolean isBingoMessagesEnabled(java.util.UUID playerId) {
        return playerSettingsService == null || playerSettingsService.isBingoMessagesEnabled(playerId);
    }

    /**
     * Exposes the on-disk plugin jar so the updater can stage a replacement with
     * the same filename into {@code plugins/update/}.
     */
    public File getPluginJarFile() {
        return getFile();
    }

    public void saveModuleConfigValues(Map<String, ?> values) {
        moduleConfigManager.saveValues(values);
    }

    public List<String> getConfigReloadTargets() {
        return moduleConfigManager.reloadTargets();
    }

    public List<String> reloadRuntimeConfig(String target) {
        String normalised = target.toLowerCase(Locale.ROOT);
        moduleConfigManager.reload(normalised);
        List<String> messages = new ArrayList<>();

        switch (normalised) {
            case "all" -> {
                reloadMediaRuntime(messages);
                reloadCoreRuntime(messages);
                reloadIntegrationsRuntime(messages);
                reloadTweaksRuntime(messages);
                reloadVoicechatRuntime(messages);
            }
            case "core" -> reloadCoreRuntime(messages);
            case "integrations" -> reloadIntegrationsRuntime(messages);
            case "chat" -> reloadChatRuntime(messages);
            case "voicechat" -> reloadVoicechatRuntime(messages);
            case "media" -> reloadMediaRuntime(messages);
            case "gameplay" -> reloadGameplayRuntime(messages);
            case "tweaks" -> reloadTweaksRuntime(messages);
            default -> throw new IllegalArgumentException(
                    "Unknown configuration reload target: " + target);
        }
        return messages;
    }

    private void reloadMediaRuntime(List<String> messages) {
        boolean mediaPolicyReloaded = MediaFeature.get().refreshConfiguration();
        messages.add(mediaPolicyReloaded
                ? "Media settings and destination policy reloaded."
                : "Media settings reloaded, but the destination policy could not be restarted.");
    }

    private void reloadCoreRuntime(List<String> messages) {
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

        reloadChatRuntime(messages);
        reloadGameplayRuntime(messages);

        if (updateService != null) {
            updateService.shutdown();
            if (getConfig().getBoolean("auto-update.enabled", true)) {
                updateService.start();
                messages.add("Auto-update scheduler restarted with current settings.");
            } else {
                messages.add("Auto-update scheduler stopped because auto-update.enabled=false.");
            }
        }

        messages.add("Restart required for voice-chat Redis settings.");
    }

    private void reloadChatRuntime(List<String> messages) {
        stopGlobalChatService();
        startGlobalChatService();
        refreshMentionAutocomplete();
        messages.add("Global chat restarted with current Redis, format, and mention settings.");
    }

    private void reloadGameplayRuntime(List<String> messages) {
        stopPlayerSettings();
        startPlayerSettings();
        messages.add("Player settings, phantom manager, and locator bar manager restarted.");
    }

    private void reloadIntegrationsRuntime(List<String> messages) {
        JadeBootstrap.disable(this);
        AppleSkinIntegration.disable(this);
        stopSimpleVoiceAnimations();
        JadeBootstrap.enable(this);
        AppleSkinIntegration.enable(this);
        startSimpleVoiceAnimations();
        messages.add(JadeBootstrap.isEnabled()
                ? "Jade integration active."
                : "Jade integration inactive (disabled in config).");
        messages.add(AppleSkinIntegration.isEnabled()
                ? "AppleSkin integration active."
                : "AppleSkin integration inactive (disabled in config).");
        messages.add(simpleVoiceAnimationsIntegration != null
                && simpleVoiceAnimationsIntegration.isActive()
                ? "Simple Voice Animations integration active."
                : "Simple Voice Animations integration inactive (disabled in config).");

        stopBingo();
        startBingo();
        messages.add(bingoManager != null
                ? "Weekly bingo tracking restarted."
                : "Weekly bingo tracking inactive (disabled in config).");

        stopSignMarkers();
        startSignMarkers();
        messages.add(signMarkerService != null
                ? "BlueMap sign markers restarted with current settings."
                : "BlueMap sign markers inactive (disabled in config or BlueMap not installed).");

        stopAccurateBlockPlacement();
        startAccurateBlockPlacement();
        messages.add(accurateBlockPlacementManager != null
                        && accurateBlockPlacementManager.isActive()
                ? "Accurate block placement protocol active."
                : "Accurate block placement protocol inactive (disabled or PacketEvents unavailable).");

        messages.add("Restart required for Xaero settings.");
    }

    private void reloadTweaksRuntime(List<String> messages) {
        stopHappyGhastSpeed();
        startHappyGhastSpeed();
        messages.add(happyGhastSpeedManager != null
                ? "Happy ghast ridden speed boost active (x" + happyGhastSpeedManager.getMultiplier() + ")."
                : "Happy ghast ridden speed boost inactive (disabled in config).");

        reloadViewDistanceManager();
        messages.add(viewDistanceManager != null
                ? "Adaptive view distance "
                        + (viewDistanceManager.isPaused() ? "paused" : "active")
                        + " (simulation "
                        + viewDistanceManager.getMinimumSimulationDistance() + "–"
                        + viewDistanceManager.getMaximumSimulationDistance() + ", view "
                        + viewDistanceManager.getMinimumViewDistance() + "–"
                        + viewDistanceManager.getMaximumViewDistance() + ")."
                : "Adaptive view distance inactive (disabled or invalid config).");

        if (unlockAllRecipesManager != null) {
            unlockAllRecipesManager.refresh();
            messages.add("Recipe unlock cache rebuilt and re-applied to online players (if enabled).");
        }

        if (customNetherPortalListener != null) {
            customNetherPortalListener.invalidate();
            messages.add("Custom nether portal settings cache cleared (re-read on next ignite).");
        }

        messages.add("Live-read gameplay tweak settings reloaded.");
    }

    private void reloadVoicechatRuntime(List<String> messages) {
        messages.add(
                "Restart required for voice chat, cross-server groups, and Lofi 24/7 CrabFM settings.");
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

    private void startBingo() {
        BingoManager manager = new BingoManager(this);
        manager.start();
        if (getConfig().getBoolean("bingo.enabled", false)) {
            this.bingoManager = manager;
        }
    }

    private void stopBingo() {
        if (bingoManager != null) {
            bingoManager.shutdown();
            bingoManager = null;
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

    private void startViewDistanceManager() {
        startViewDistanceManager(new ViewDistanceManager(this));
    }

    private void startViewDistanceManager(ViewDistanceManager manager) {
        if (!manager.isEnabled()) {
            return;
        }
        this.viewDistanceManager = manager;
        manager.start();
        getLogger().info("Adaptive view distance enabled (simulation "
                + manager.getMinimumSimulationDistance() + "–"
                + manager.getMaximumSimulationDistance() + ", view "
                + manager.getMinimumViewDistance() + "–"
                + manager.getMaximumViewDistance() + ")");
    }

    private void reloadViewDistanceManager() {
        boolean wasPaused = viewDistanceManager != null && viewDistanceManager.isPaused();
        ViewDistanceManager replacement = new ViewDistanceManager(this);
        if (viewDistanceManager != null) {
            viewDistanceManager.shutdown(!replacement.isEnabled());
            viewDistanceManager = null;
        }
        startViewDistanceManager(replacement);
        if (wasPaused && viewDistanceManager != null) {
            viewDistanceManager.pause();
        }
    }

    private void stopViewDistanceManager(boolean restoreActualRadius) {
        if (viewDistanceManager != null) {
            viewDistanceManager.shutdown(restoreActualRadius);
            viewDistanceManager = null;
        }
    }

    private void startAccurateBlockPlacement() {
        AccurateBlockPlacementManager manager = new AccurateBlockPlacementManager(this);
        this.accurateBlockPlacementManager = manager;
        if (manager.start(getConfig().getBoolean(
                "mod-protocols.accurate-block-placement.enabled",
                false))) {
            getLogger().info(
                    "Accurate block placement enabled (Carpet protocol v2 via PacketEvents).");
        }
    }

    private void stopAccurateBlockPlacement() {
        if (accurateBlockPlacementManager != null) {
            accurateBlockPlacementManager.shutdown();
            accurateBlockPlacementManager = null;
        }
    }

    private void startSimpleVoiceAnimations() {
        if (!getConfig().getBoolean(
                "mod-protocols.simple-voice-animations.enabled",
                true)) {
            getLogger().info("Simple Voice Animations integration disabled in config.");
            return;
        }

        this.simpleVoiceAnimationsIntegration =
                new SimpleVoiceAnimationsIntegration(this);
        simpleVoiceAnimationsIntegration.start();
        getLogger().info("Simple Voice Animations integration enabled.");
    }

    private void stopSimpleVoiceAnimations() {
        if (simpleVoiceAnimationsIntegration != null) {
            simpleVoiceAnimationsIntegration.shutdown();
            simpleVoiceAnimationsIntegration = null;
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
        stopSimpleVoiceAnimations();
        stopAccurateBlockPlacement();
        stopStatsPushTask();
        if (updateService != null) {
            updateService.shutdown();
        }
        if (voicechatRegistration != null) {
            // Never let voice-bridge cleanup abort the rest of onDisable.
            try {
                voicechatRegistration.close();
            } catch (Exception e) {
                getLogger().warning("Voice bridge shutdown failed: " + e.getMessage());
            }
            voicechatRegistration = null;
        }
        // Voice-chat playback must stop before its shared media engine.
        MediaFeature.disable();
        stopLoginStreakCache();
        if (nicknameSync != null) {
            nicknameSync.shutdown();
            nicknameSync = null;
        }
        if (chatBridge != null) {
            chatBridge.shutdown();
            chatBridge = null;
        }
        stopGlobalChatService();
        stopBingo();
        stopPlayerSettings();
        stopSignMarkers();
        stopHappyGhastSpeed();
        stopViewDistanceManager(true);
        stopAccurateBlockPlacement();
        JadeBootstrap.disable(this);
        AppleSkinIntegration.disable(this);
    }
}
