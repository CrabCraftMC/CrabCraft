package crabcraft.net.crabUtilities

import crabcraft.net.crabUtilities.accurateplacement.AccurateBlockPlacementManager
import crabcraft.net.crabUtilities.appleskin.AppleSkinIntegration
import crabcraft.net.crabUtilities.awards.EatingAwardTracker
import crabcraft.net.crabUtilities.awards.SuspiciousBrushTracker
import crabcraft.net.crabUtilities.bluemap.SignMarkerService
import crabcraft.net.crabUtilities.bingo.BingoManager
import crabcraft.net.crabUtilities.cauldron.CauldronRecipeListener
import crabcraft.net.crabUtilities.chat.*
import crabcraft.net.crabUtilities.chat.bridge.PaperChatBridge
import crabcraft.net.crabUtilities.config.ModuleConfigManager
import crabcraft.net.crabUtilities.coordinates.CoordinateHelperCommand
import crabcraft.net.crabUtilities.enderman.EndermanGriefListener
import crabcraft.net.crabUtilities.endportals.EndPortalBlockerListener
import crabcraft.net.crabUtilities.happyghast.HappyGhastSpeedManager
import crabcraft.net.crabUtilities.heads.PersistentHeadsListener
import crabcraft.net.crabUtilities.heads.PlayerHeadDropsListener
import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.netherportals.CustomNetherPortalListener
import crabcraft.net.crabUtilities.recipes.UnlockAllRecipesManager
import crabcraft.net.crabUtilities.restrictedarea.RestrictedAreaListener
import crabcraft.net.crabUtilities.settings.*
import crabcraft.net.crabUtilities.shulker.ShulkerShellListener
import crabcraft.net.crabUtilities.slime.SlimeCommand
import crabcraft.net.crabUtilities.slime.SlimeMapListener
import crabcraft.net.crabUtilities.sleep.SleepBroadcastListener
import crabcraft.net.crabUtilities.spectator.SpectatorBackCommand
import crabcraft.net.crabUtilities.xaero.XaeroBootstrap
import crabcraft.net.crabUtilities.xpclumps.ExperienceClumpListener
import crabcraft.net.crabUtilities.update.UpdateCommand
import crabcraft.net.crabUtilities.update.UpdateService
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceCommand
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceManager
import crabcraft.net.crabUtilities.villagers.SharedVillagerDiscountListener
import crabcraft.net.crabUtilities.voicechat.SimpleVoiceAnimationsIntegration
import crabcraft.net.crabUtilities.voicechat.VoicechatIntegration
import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.model.ModelCommand
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

import java.io.File
import java.util.ArrayList
import java.util.Locale

class CrabUtilities : JavaPlugin() {

    private var essentials: Plugin? = null // Optional: present when EssentialsX is installed
    private var statsPushTask: StatsPushTask? = null
    private var updateService: UpdateService? = null
    private var voicechatRegistration: AutoCloseable? = null
    private var loginStreakCache: LoginStreakCache? = null
    private var loginStreakExpansion: LoginStreakExpansion? = null
    private var globalChatService: GlobalChatService? = null
    private var globalChatListener: GlobalChatListener? = null
    private var chatBridge: PaperChatBridge? = null
    private var mentionAutocompleteListener: MentionAutocompleteListener? = null
    private var playerSettingsService: PlayerSettingsService? = null
    private var phantomManager: PhantomManager? = null
    private var locatorBarManager: LocatorBarManager? = null
    private var coordinateHudManager: CoordinateHudManager? = null
    private var settingsDialog: SettingsDialog? = null
    private var signMarkerService: SignMarkerService? = null
    private var happyGhastSpeedManager: HappyGhastSpeedManager? = null
    private var unlockAllRecipesManager: UnlockAllRecipesManager? = null
    private var customNetherPortalListener: CustomNetherPortalListener? = null
    private var nicknameSync: NicknameSync? = null
    private var viewDistanceManager: ViewDistanceManager? = null
    private var accurateBlockPlacementManager: AccurateBlockPlacementManager? = null
    private var simpleVoiceAnimationsIntegration: SimpleVoiceAnimationsIntegration? = null
    private var moduleConfigManager: ModuleConfigManager? = null
    private var bingoManager: BingoManager? = null
    private var restrictedAreaListener: RestrictedAreaListener? = null

    override fun onEnable() {
        // Detect EssentialsX (optional) and register event listeners
        this.essentials = Bukkit.getPluginManager().getPlugin("Essentials")

        // Write any missing core/module defaults and expose one merged
        // in-memory view to existing configuration consumers. Legacy values
        // are deliberately not migrated administrators move them manually.
        saveDefaultConfig()
        this.moduleConfigManager = ModuleConfigManager(this)
        moduleConfigManager!!.initialise()

        // Discs, horns and the shared yt-dlp/FFmpeg media pipeline.
        MediaFeature.enable(this)

        // SVC plugin-message channels — required to inject fake PlayerStatePackets
        // for cross-server group GUI roster.
        getServer().getMessenger().registerOutgoingPluginChannel(this, "voicechat:state")
        getServer().getMessenger().registerOutgoingPluginChannel(this, "voicechat:remove_state")

        // Jade server-side companion (block/entity tooltip data for the Jade client mod).
        JadeBootstrap.enable(this)

        // AppleSkin server-side companion (saturation/exhaustion sync).
        AppleSkinIntegration.enable(this)

        // Simple Voice Animations server-side companion (per-player head settings sync).
        startSimpleVoiceAnimations()

        // Xaero map identity (separates backend map storage behind a proxy).
        XaeroBootstrap.enable(this)

        // Carpet accurate-block-placement protocol for Litematica and
        // Tweakeroo EasyPlace clients.
        startAccurateBlockPlacement()

        // Event listeners
        Bukkit.getPluginManager().registerEvents(NicknameMessageListener(this), this)
        if (essentials != null) {
            this.nicknameSync = NicknameSync(this)
            Bukkit.getPluginManager().registerEvents(nicknameSync!!, this)
            nicknameSync!!.start()
        } else {
            getLogger().info("EssentialsX not detected — nickname Redis sync disabled.")
        }
        this.mentionAutocompleteListener = MentionAutocompleteListener(this)
        Bukkit.getPluginManager().registerEvents(mentionAutocompleteListener!!, this)
        if (essentials != null) {
            Bukkit.getPluginManager().registerEvents(
                    EssentialsMentionAutocompleteListener(this, mentionAutocompleteListener!!), this)
        }
        mentionAutocompleteListener!!.refreshAll()

        this.restrictedAreaListener = RestrictedAreaListener(this)
        Bukkit.getPluginManager().registerEvents(restrictedAreaListener!!, this)

        // Sleep broadcast: announce who slept when the night is skipped. Opt-in
        // and disabled by default the listener reads config live, so
        // /crabutilities reload toggles it without re-registration.
        Bukkit.getPluginManager().registerEvents(SleepBroadcastListener(this), this)

        // Custom nether portals: allow non-rectangular / custom-size portals.
        // Opt-in and disabled by default the enabled flag is read live so
        // /crabutilities reload toggles it without re-registration. The parsed
        // frame-materials/size settings are cached and invalidated on reload.
        this.customNetherPortalListener = CustomNetherPortalListener(this)
        Bukkit.getPluginManager().registerEvents(customNetherPortalListener!!, this)

        // End portal blocker: prevent every entity from entering an End portal.
        // Opt-in and disabled by default the listener reads config live so
        // /crabutilities reload tweaks toggles it without re-registration.
        Bukkit.getPluginManager().registerEvents(EndPortalBlockerListener(this), this)

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
        Bukkit.getPluginManager().registerEvents(CauldronRecipeListener(this), this)
        Bukkit.getPluginManager().registerEvents(EndermanGriefListener(this), this)
        Bukkit.getPluginManager().registerEvents(ShulkerShellListener(this), this)
        Bukkit.getPluginManager().registerEvents(PlayerHeadDropsListener(this), this)
        Bukkit.getPluginManager().registerEvents(PersistentHeadsListener(this), this)
        Bukkit.getPluginManager().registerEvents(ExperienceClumpListener(this), this)
        Bukkit.getPluginManager().registerEvents(SharedVillagerDiscountListener(this), this)
        Bukkit.getPluginManager().registerEvents(SuspiciousBrushTracker(), this)
        Bukkit.getPluginManager().registerEvents(EatingAwardTracker(), this)
        Bukkit.getPluginManager().registerEvents(SlimeMapListener(), this)

        startAccurateBlockPlacement()

        // Unlock-all-recipes: unlock every recipe on join (and for anyone
        // already online). Caches the recipe key set the cache is rebuilt and
        // re-applied on /crabutilities reload.
        this.unlockAllRecipesManager = UnlockAllRecipesManager(this)
        Bukkit.getPluginManager().registerEvents(unlockAllRecipesManager!!, this)
        unlockAllRecipesManager!!.start()

        // Auto-updater
        this.updateService = UpdateService(this)
        val updateCommand = UpdateCommand(this, updateService!!)
        startUpdateService()

        // Commands
        val viewDistanceCommand = ViewDistanceCommand { viewDistanceManager }
        val reloadCommand = ReloadCommand(this, updateCommand, viewDistanceCommand)
        getCommand("crabutilities")!!.setExecutor(reloadCommand)
        getCommand("crabutilities")!!.setTabCompleter(reloadCommand)

        val slimeCommand = SlimeCommand()
        getCommand("slime")!!.setExecutor(slimeCommand)
        getCommand("slime")!!.setTabCompleter(slimeCommand)

        val spectatorBackCommand = SpectatorBackCommand()
        Bukkit.getPluginManager().registerEvents(spectatorBackCommand, this)
        getCommand("specback")!!.setExecutor(spectatorBackCommand)

        getCommand("portalcoords")!!.setExecutor(CoordinateHelperCommand())

        val modelCommand = ModelCommand.create(this)
        Bukkit.getPluginManager().registerEvents(modelCommand, this)
        Bukkit.getPluginManager().registerEvents(modelCommand.protectionListener(), this)
        getCommand("model")!!.setExecutor(modelCommand)
        getCommand("model")!!.setTabCompleter(modelCommand)

        // Private and staff chat enter through Paper so InteractiveChat, emoji
        // plugins and other local processors see them before Velocity routes
        // them across the network.
        this.chatBridge = PaperChatBridge(this)
        chatBridge!!.start()

        startStatsPushTask()

        // Login streaks: read-only mirror of the Velocity-owned data,
        // populated via Redis. Soft dependency on PlaceholderAPI — if
        // it's not loaded, the cache still runs (other features could
        // consume it) but the placeholder expansion is skipped.
        startLoginStreakCache()

        // Global chat: on servers where it's enabled, capture/format/sync
        // normal chat across the network over Redis. Disabled servers stay
        // fully local.
        startGlobalChatService()

        // Per-player settings (/settings) and the gameplay toggles they drive.
        // Settings are stored network-wide in Redis phantoms and locator bar default OFF.
        startPlayerSettings()

        // Weekly bingo is opt-out for players but feature-gated for deployment.
        startBingo()

        // BlueMap sign markers: signs with [map] on the top line become POI
        // markers on the BlueMap web map. Soft dependency — skipped when the
        // BlueMap plugin isn't installed or the feature is off in config.
        startSignMarkers()

        // Happy ghast ridden speed boost: while a player is riding, a transient
        // flying_speed modifier makes the ghast faster. Disabled by default.
        startHappyGhastSpeed()

        // Adapt simulation and view distances to server tick time. Disabled by
        // default the manager owns its tick listener and repeating task.
        startViewDistanceManager()

        // Simple Voice Chat integration: mirrors group definitions and bridges
        // grouped voice across backends via Redis.
        // Soft dependency — skipped silently if the SVC plugin isn't installed.
        if (Bukkit.getPluginManager().getPlugin("voicechat") != null) {
            try {
                this.voicechatRegistration = VoicechatIntegration.register(this)
            } catch (e: LinkageError) {
                getLogger().warning("Simple Voice Chat present but API classes not usable: " + e.message)
            }
        }

        getLogger().info("CrabUtilities enabled. EssentialsX present: " + (essentials != null))
    }

    fun getEssentials(): Plugin? {
        return essentials
    }

    fun refreshMentionAutocomplete() {
        if (mentionAutocompleteListener != null) {
            mentionAutocompleteListener!!.refreshAll()
        }
    }

    /** The per-player settings mirror, or {@code null} before it has started. */
    fun getPlayerSettingsService(): PlayerSettingsService? {
        return playerSettingsService
    }

    fun isBingoMessagesEnabled(playerId: java.util.UUID): Boolean {
        return playerSettingsService == null || playerSettingsService!!.isBingoMessagesEnabled(playerId)
    }

    /**
     * Exposes the on-disk plugin jar so the updater can stage a replacement with
     * the same filename into {@code plugins/update/}.
     */
    fun getPluginJarFile(): File {
        return getFile()
    }

    fun saveModuleConfigValues(values: Map<String, *>) {
        moduleConfigManager!!.saveValues(values)
    }

    fun getConfigReloadTargets(): List<String> {
        return moduleConfigManager!!.reloadTargets()
    }

    fun reloadRuntimeConfig(target: String): MutableList<String> {
        val normalised = target.lowercase(Locale.ROOT)
        moduleConfigManager!!.reload(normalised)
        val messages = ArrayList<String>()

        when (normalised) {
            "all" -> {
                reloadMediaRuntime(messages)
                reloadCoreRuntime(messages)
                reloadIntegrationsRuntime(messages)
                reloadTweaksRuntime(messages)
                reloadVoicechatRuntime(messages)
            }
            "core" -> reloadCoreRuntime(messages)
            "integrations" -> reloadIntegrationsRuntime(messages)
            "chat" -> reloadChatRuntime(messages)
            "voicechat" -> reloadVoicechatRuntime(messages)
            "media" -> reloadMediaRuntime(messages)
            "gameplay" -> reloadGameplayRuntime(messages)
            "tweaks" -> reloadTweaksRuntime(messages)
            else -> throw IllegalArgumentException(
                    "Unknown configuration reload target: " + target)
        }
        return messages
    }

    private fun reloadMediaRuntime(messages: MutableList<String>) {
        val mediaPolicyReloaded = MediaFeature.get().refreshConfiguration()
        messages.add(if (mediaPolicyReloaded)
                "Media settings and destination policy reloaded."
                else "Media settings reloaded, but the destination policy could not be restarted.")
    }

    private fun reloadCoreRuntime(messages: MutableList<String>) {
        stopStatsPushTask()
        startStatsPushTask()
        messages.add("Stats push restarted with current season, Redis, and interval settings.")

        stopLoginStreakCache()
        startLoginStreakCache()
        messages.add("Login streak cache restarted with current Redis settings.")

        if (nicknameSync != null) {
            nicknameSync!!.shutdown()
            nicknameSync!!.start()
            messages.add("Nickname Redis sync restarted with current Redis settings.")
        }

        reloadChatRuntime(messages)
        reloadGameplayRuntime(messages)

        if (updateService != null) {
            updateService!!.shutdown()
            if (getConfig().getBoolean("auto-update.enabled", true)) {
                updateService!!.start()
                messages.add("Auto-update scheduler restarted with current settings.")
            } else {
                messages.add("Auto-update scheduler stopped because auto-update.enabled=false.")
            }
        }

        messages.add("Restart required for voice-chat Redis settings.")
    }

    private fun reloadChatRuntime(messages: MutableList<String>) {
        stopGlobalChatService()
        startGlobalChatService()
        refreshMentionAutocomplete()
        messages.add("Global chat restarted with current Redis, format, and mention settings.")
    }

    private fun reloadGameplayRuntime(messages: MutableList<String>) {
        if (restrictedAreaListener != null) {
            restrictedAreaListener!!.refresh()
        }
        stopPlayerSettings()
        startPlayerSettings()
        messages.add("Player settings and unverified-player protection reloaded.")
    }

    private fun reloadIntegrationsRuntime(messages: MutableList<String>) {
        JadeBootstrap.disable(this)
        AppleSkinIntegration.disable(this)
        stopSimpleVoiceAnimations()
        JadeBootstrap.enable(this)
        AppleSkinIntegration.enable(this)
        startSimpleVoiceAnimations()
        messages.add(if (JadeBootstrap.isEnabled())
                "Jade integration active."
                else "Jade integration inactive (disabled in config).")
        messages.add(if (AppleSkinIntegration.isEnabled())
                "AppleSkin integration active."
                else "AppleSkin integration inactive (disabled in config).")
        messages.add(if (simpleVoiceAnimationsIntegration != null
                && simpleVoiceAnimationsIntegration!!.isActive())
                "Simple Voice Animations integration active."
                else "Simple Voice Animations integration inactive (disabled in config).")

        stopBingo()
        startBingo()
        messages.add(if (bingoManager != null)
                "Weekly bingo tracking restarted."
                else "Weekly bingo tracking inactive (disabled in config).")

        stopSignMarkers()
        startSignMarkers()
        messages.add(if (signMarkerService != null)
                "BlueMap sign markers restarted with current settings."
                else "BlueMap sign markers inactive (disabled in config or BlueMap not installed).")

        stopAccurateBlockPlacement()
        startAccurateBlockPlacement()
        messages.add(if (accurateBlockPlacementManager != null
                        && accurateBlockPlacementManager!!.isActive())
                "Accurate block placement protocol active."
                else "Accurate block placement protocol inactive (disabled or PacketEvents unavailable).")

        messages.add("Restart required for Xaero settings.")
    }

    private fun reloadTweaksRuntime(messages: MutableList<String>) {
        stopHappyGhastSpeed()
        startHappyGhastSpeed()
        messages.add(if (happyGhastSpeedManager != null)
                "Happy ghast ridden speed boost active (x" + happyGhastSpeedManager!!.getMultiplier() + ")."
                else "Happy ghast ridden speed boost inactive (disabled in config).")

        reloadViewDistanceManager()
        messages.add(if (viewDistanceManager != null)
                "Adaptive view distance "
                        + (if (viewDistanceManager!!.isPaused()) "paused" else "active")
                        + " (simulation "
                        + viewDistanceManager!!.getMinimumSimulationDistance() + "–"
                        + viewDistanceManager!!.getMaximumSimulationDistance() + ", view "
                        + viewDistanceManager!!.getMinimumViewDistance() + "–"
                        + viewDistanceManager!!.getMaximumViewDistance() + ")."
                else "Adaptive view distance inactive (disabled or invalid config).")

        if (unlockAllRecipesManager != null) {
            unlockAllRecipesManager!!.refresh()
            messages.add("Recipe unlock cache rebuilt and re-applied to online players (if enabled).")
        }

        if (customNetherPortalListener != null) {
            customNetherPortalListener!!.invalidate()
            messages.add("Custom nether portal settings cache cleared (re-read on next ignite).")
        }

        messages.add("Live-read gameplay tweak settings reloaded.")
    }

    private fun reloadVoicechatRuntime(messages: MutableList<String>) {
        messages.add(
                "Restart required for voice chat, cross-server groups, and Lofi 24/7 CrabFM settings.")
    }

    private fun startUpdateService() {
        if (updateService != null && getConfig().getBoolean("auto-update.enabled", true)) {
            updateService!!.start()
        }
    }

    private fun startStatsPushTask() {
        this.statsPushTask = StatsPushTask(this)
        statsPushTask!!.start()
    }

    private fun stopStatsPushTask() {
        if (statsPushTask != null) {
            statsPushTask!!.shutdown()
            statsPushTask = null
        }
    }

    private fun startLoginStreakCache() {
        this.loginStreakCache = LoginStreakCache(this)
        loginStreakCache!!.start()
        Bukkit.getPluginManager().registerEvents(loginStreakCache!!, this)
        registerLoginStreakExpansion()
    }

    private fun stopLoginStreakCache() {
        if (loginStreakExpansion != null) {
            try { loginStreakExpansion!!.unregister() } catch (ignored: Throwable) {}
            loginStreakExpansion = null
        }
        if (loginStreakCache != null) {
            HandlerList.unregisterAll(loginStreakCache!!)
            loginStreakCache!!.shutdown()
            loginStreakCache = null
        }
    }

    private fun registerLoginStreakExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                this.loginStreakExpansion = LoginStreakExpansion(this, loginStreakCache!!)
                if (loginStreakExpansion!!.register()) {
                    getLogger().info("Registered PlaceholderAPI expansion 'crabutilities'")
                } else {
                    getLogger().warning("PlaceholderAPI expansion registration returned false")
                }
            } catch (e: NoClassDefFoundError) {
                getLogger().warning("PlaceholderAPI present but classes not visible: " + e.message)
            }
        } else {
            getLogger().info("PlaceholderAPI not detected — streak placeholders disabled.")
        }
    }

    private fun startGlobalChatService() {
        this.globalChatService = GlobalChatService(this)
        globalChatService!!.start()
        this.globalChatListener = GlobalChatListener(globalChatService!!)
        Bukkit.getPluginManager().registerEvents(globalChatListener!!, this)
    }

    private fun startPlayerSettings() {
        this.playerSettingsService = PlayerSettingsService(this)
        this.phantomManager = PhantomManager(this, playerSettingsService!!)
        this.locatorBarManager = LocatorBarManager(this, playerSettingsService!!)
        this.coordinateHudManager = CoordinateHudManager(this, playerSettingsService!!)
        this.settingsDialog = SettingsDialog(playerSettingsService!!)

        Bukkit.getPluginManager().registerEvents(playerSettingsService!!, this)
        Bukkit.getPluginManager().registerEvents(phantomManager!!, this)
        Bukkit.getPluginManager().registerEvents(locatorBarManager!!, this)
        Bukkit.getPluginManager().registerEvents(coordinateHudManager!!, this)

        coordinateHudManager!!.start()
        locatorBarManager!!.start()
        playerSettingsService!!.start()
        phantomManager!!.start()

        val settingsCommand = SettingsCommand(playerSettingsService!!, settingsDialog!!)
        if (getCommand("settings") != null) {
            getCommand("settings")!!.setExecutor(settingsCommand)
            getCommand("settings")!!.setTabCompleter(settingsCommand)
        }
    }

    private fun stopPlayerSettings() {
        this.settingsDialog = null
        if (locatorBarManager != null) {
            HandlerList.unregisterAll(locatorBarManager!!)
            locatorBarManager = null
        }
        if (phantomManager != null) {
            HandlerList.unregisterAll(phantomManager!!)
            phantomManager = null
        }
        if (coordinateHudManager != null) {
            HandlerList.unregisterAll(coordinateHudManager!!)
            coordinateHudManager!!.shutdown()
            coordinateHudManager = null
        }
        if (playerSettingsService != null) {
            HandlerList.unregisterAll(playerSettingsService!!)
            playerSettingsService!!.shutdown()
            playerSettingsService = null
        }
    }

    private fun startBingo() {
        val manager = BingoManager(this)
        manager.start()
        if (getConfig().getBoolean("bingo.enabled", false)) {
            this.bingoManager = manager
        }
    }

    private fun stopBingo() {
        if (bingoManager != null) {
            bingoManager!!.shutdown()
            bingoManager = null
        }
    }

    private fun startSignMarkers() {
        if (!getConfig().getBoolean("bluemap.sign-markers.enabled", false)) {
            return
        }
        if (Bukkit.getPluginManager().getPlugin("BlueMap") == null) {
            getLogger().info("BlueMap not detected — sign markers disabled.")
            return
        }
        // Same pattern as the PlaceholderAPI expansion: SignMarkerService is
        // the only class referencing the BlueMap API, and it is only loaded
        // here, after BlueMap's presence has been confirmed. LinkageError (not
        // just NoClassDefFoundError) because the first BlueMap-class touch is
        // a method-ref bootstrap, whose failure surfaces as BootstrapMethodError.
        try {
            this.signMarkerService = SignMarkerService(this)
            signMarkerService!!.start()
            getLogger().info("BlueMap sign markers enabled (keyword: " + signMarkerService!!.getKeyword() + ")")
        } catch (e: LinkageError) {
            this.signMarkerService = null
            getLogger().warning("BlueMap present but API classes not usable: " + e.message)
        }
    }

    private fun stopSignMarkers() {
        if (signMarkerService != null) {
            signMarkerService!!.shutdown()
            signMarkerService = null
        }
    }

    private fun startHappyGhastSpeed() {
        val manager = HappyGhastSpeedManager(this)
        if (!manager.isEnabled()) {
            return
        }
        this.happyGhastSpeedManager = manager
        Bukkit.getPluginManager().registerEvents(manager, this)
        manager.start()
        getLogger().info("Happy ghast ridden speed boost enabled (x" + manager.getMultiplier() + ")")
    }

    private fun stopHappyGhastSpeed() {
        if (happyGhastSpeedManager != null) {
            HandlerList.unregisterAll(happyGhastSpeedManager!!)
            happyGhastSpeedManager!!.shutdown()
            happyGhastSpeedManager = null
        }
    }

    private fun startViewDistanceManager() {
        startViewDistanceManager(ViewDistanceManager(this))
    }

    private fun startViewDistanceManager(manager: ViewDistanceManager) {
        if (!manager.isEnabled()) {
            return
        }
        this.viewDistanceManager = manager
        manager.start()
        getLogger().info("Adaptive view distance enabled (simulation "
                + manager.getMinimumSimulationDistance() + "–"
                + manager.getMaximumSimulationDistance() + ", view "
                + manager.getMinimumViewDistance() + "–"
                + manager.getMaximumViewDistance() + ")")
    }

    private fun reloadViewDistanceManager() {
        val wasPaused = viewDistanceManager != null && viewDistanceManager!!.isPaused()
        val replacement = ViewDistanceManager(this)
        if (viewDistanceManager != null) {
            viewDistanceManager!!.shutdown(!replacement.isEnabled())
            viewDistanceManager = null
        }
        startViewDistanceManager(replacement)
        if (wasPaused && viewDistanceManager != null) {
            viewDistanceManager!!.pause()
        }
    }

    private fun stopViewDistanceManager(restoreActualRadius: Boolean) {
        if (viewDistanceManager != null) {
            viewDistanceManager!!.shutdown(restoreActualRadius)
            viewDistanceManager = null
        }
    }

    private fun startAccurateBlockPlacement() {
        val manager = AccurateBlockPlacementManager(this)
        this.accurateBlockPlacementManager = manager
        if (manager.start(getConfig().getBoolean(
                "mod-protocols.accurate-block-placement.enabled",
                false))) {
            getLogger().info(
                    "Accurate block placement enabled (Carpet protocol v2 via PacketEvents).")
        }
    }

    private fun stopAccurateBlockPlacement() {
        if (accurateBlockPlacementManager != null) {
            accurateBlockPlacementManager!!.shutdown()
            accurateBlockPlacementManager = null
        }
    }

    private fun startSimpleVoiceAnimations() {
        if (!getConfig().getBoolean(
                "mod-protocols.simple-voice-animations.enabled",
                true)) {
            getLogger().info("Simple Voice Animations integration disabled in config.")
            return
        }

        this.simpleVoiceAnimationsIntegration =
                SimpleVoiceAnimationsIntegration(this)
        simpleVoiceAnimationsIntegration!!.start()
        getLogger().info("Simple Voice Animations integration enabled.")
    }

    private fun stopSimpleVoiceAnimations() {
        if (simpleVoiceAnimationsIntegration != null) {
            simpleVoiceAnimationsIntegration!!.shutdown()
            simpleVoiceAnimationsIntegration = null
        }
    }

    private fun stopGlobalChatService() {
        if (globalChatListener != null) {
            HandlerList.unregisterAll(globalChatListener!!)
            globalChatListener = null
        }
        if (globalChatService != null) {
            globalChatService!!.shutdown()
            globalChatService = null
        }
    }

    override fun onDisable() {
        stopSimpleVoiceAnimations()
        stopAccurateBlockPlacement()
        stopStatsPushTask()
        if (updateService != null) {
            updateService!!.shutdown()
        }
        if (voicechatRegistration != null) {
            // Never let voice-bridge cleanup abort the rest of onDisable.
            try {
                voicechatRegistration!!.close()
            } catch (e: Exception) {
                getLogger().warning("Voice bridge shutdown failed: " + e.message)
            }
            voicechatRegistration = null
        }
        // Voice-chat playback must stop before its shared media engine.
        MediaFeature.disable()
        stopLoginStreakCache()
        if (nicknameSync != null) {
            nicknameSync!!.shutdown()
            nicknameSync = null
        }
        if (chatBridge != null) {
            chatBridge!!.shutdown()
            chatBridge = null
        }
        stopGlobalChatService()
        stopBingo()
        stopPlayerSettings()
        stopSignMarkers()
        stopHappyGhastSpeed()
        stopViewDistanceManager(true)
        stopAccurateBlockPlacement()
        JadeBootstrap.disable(this)
        AppleSkinIntegration.disable(this)
    }

}
