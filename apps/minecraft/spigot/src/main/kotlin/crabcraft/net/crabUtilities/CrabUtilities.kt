package crabcraft.net.crabUtilities

import crabcraft.net.crabUtilities.accurateplacement.AccurateBlockPlacementManager
import crabcraft.net.crabUtilities.appleskin.AppleSkinIntegration
import crabcraft.net.crabUtilities.awards.EatingAwardTracker
import crabcraft.net.crabUtilities.awards.SuspiciousBrushTracker
import crabcraft.net.crabUtilities.bingo.BingoManager
import crabcraft.net.crabUtilities.bluemap.SignMarkerService
import crabcraft.net.crabUtilities.cauldron.CauldronRecipeListener
import crabcraft.net.crabUtilities.chat.*
import crabcraft.net.crabUtilities.chat.bridge.PaperChatBridge
import crabcraft.net.crabUtilities.config.ModuleConfigManager
import crabcraft.net.crabUtilities.coordinates.CoordinateHelperCommand
import crabcraft.net.crabUtilities.enderman.EndermanGriefListener
import crabcraft.net.crabUtilities.endportals.EndPortalBlockerListener
import crabcraft.net.crabUtilities.halloween.HalloweenManager
import crabcraft.net.crabUtilities.happyghast.HappyGhastSpeedManager
import crabcraft.net.crabUtilities.heads.PersistentHeadsListener
import crabcraft.net.crabUtilities.heads.PlayerHeadDropsListener
import crabcraft.net.crabUtilities.jade.JadeBootstrap
import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.model.ModelCommand
import crabcraft.net.crabUtilities.netherportals.CustomNetherPortalListener
import crabcraft.net.crabUtilities.recipes.UnlockAllRecipesManager
import crabcraft.net.crabUtilities.restrictedarea.RestrictedAreaListener
import crabcraft.net.crabUtilities.settings.*
import crabcraft.net.crabUtilities.shulker.ShulkerShellListener
import crabcraft.net.crabUtilities.sleep.SleepBroadcastListener
import crabcraft.net.crabUtilities.slime.SlimeCommand
import crabcraft.net.crabUtilities.slime.SlimeMapListener
import crabcraft.net.crabUtilities.spectator.SpectatorBackCommand
import crabcraft.net.crabUtilities.update.UpdateCommand
import crabcraft.net.crabUtilities.update.UpdateService
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceCommand
import crabcraft.net.crabUtilities.viewdistance.ViewDistanceManager
import crabcraft.net.crabUtilities.villagers.SharedVillagerDiscountListener
import crabcraft.net.crabUtilities.voicechat.SimpleVoiceAnimationsIntegration
import crabcraft.net.crabUtilities.voicechat.VoicechatIntegration
import crabcraft.net.crabUtilities.xaero.XaeroBootstrap
import crabcraft.net.crabUtilities.xpclumps.ExperienceClumpListener
import java.io.File
import java.util.ArrayList
import java.util.Locale
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin

class CrabUtilities : JavaPlugin() {
    private var essentials: Plugin? = null
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
    private lateinit var moduleConfigManager: ModuleConfigManager
    private var bingoManager: BingoManager? = null
    private var halloweenManager: HalloweenManager? = null
    private var restrictedAreaListener: RestrictedAreaListener? = null
    private var vanishStatusPublisher: VanishStatusPublisher? = null

    override fun onEnable() {
        essentials = Bukkit.getPluginManager().getPlugin("Essentials")
        // Expose merged defaults. Administrators migrate legacy values manually.
        saveDefaultConfig()
        moduleConfigManager = ModuleConfigManager(this)
        moduleConfigManager.initialise()
        // Publish authoritative vanish state before publicly exposing players.
        val vanishPublisher = VanishStatusPublisher(this)
        vanishStatusPublisher = vanishPublisher
        vanishPublisher.start()
        if (essentials != null)
            Bukkit.getPluginManager().registerEvents(EssentialsVanishListener(this, vanishPublisher), this)
        MediaFeature.enable(this)
        server.messenger.registerOutgoingPluginChannel(this, "voicechat:state")
        server.messenger.registerOutgoingPluginChannel(this, "voicechat:remove_state")
        JadeBootstrap.enable(this)
        AppleSkinIntegration.enable(this)
        startSimpleVoiceAnimations()
        XaeroBootstrap.enable(this)
        startAccurateBlockPlacement()
        Bukkit.getPluginManager().registerEvents(NicknameMessageListener(this), this)
        if (essentials != null) {
            val sync = NicknameSync(this)
            nicknameSync = sync
            Bukkit.getPluginManager().registerEvents(sync, this)
            sync.start()
        } else {
            logger.info("EssentialsX not detected — nickname Redis sync disabled.")
        }
        val autocomplete = MentionAutocompleteListener(this)
        mentionAutocompleteListener = autocomplete
        Bukkit.getPluginManager().registerEvents(autocomplete, this)
        if (essentials != null)
            Bukkit.getPluginManager().registerEvents(EssentialsMentionAutocompleteListener(this, autocomplete), this)
        autocomplete.refreshAll()
        val restrictedArea = RestrictedAreaListener(this)
        restrictedAreaListener = restrictedArea
        Bukkit.getPluginManager().registerEvents(restrictedArea, this)
        // These listeners read their enabled flag live on configuration reload.
        Bukkit.getPluginManager().registerEvents(SleepBroadcastListener(this), this)
        val portals = CustomNetherPortalListener(this)
        customNetherPortalListener = portals
        Bukkit.getPluginManager().registerEvents(portals, this)
        Bukkit.getPluginManager().registerEvents(EndPortalBlockerListener(this), this)
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
        val recipes = UnlockAllRecipesManager(this)
        unlockAllRecipesManager = recipes
        Bukkit.getPluginManager().registerEvents(recipes, this)
        recipes.start()
        val updater = UpdateService(this)
        updateService = updater
        val updateCommand = UpdateCommand(this, updater)
        startUpdateService()
        val reloadCommand = ReloadCommand(this, updateCommand, ViewDistanceCommand { viewDistanceManager })
        getCommand("crabutilities")!!.setExecutor(reloadCommand)
        getCommand("crabutilities")!!.tabCompleter = reloadCommand
        val slimeCommand = SlimeCommand()
        getCommand("slime")!!.setExecutor(slimeCommand)
        getCommand("slime")!!.tabCompleter = slimeCommand
        val spectatorBackCommand = SpectatorBackCommand()
        Bukkit.getPluginManager().registerEvents(spectatorBackCommand, this)
        getCommand("specback")!!.setExecutor(spectatorBackCommand)
        getCommand("portalcoords")!!.setExecutor(CoordinateHelperCommand())
        val modelCommand = ModelCommand.create(this)
        Bukkit.getPluginManager().registerEvents(modelCommand, this)
        Bukkit.getPluginManager().registerEvents(modelCommand.protectionListener(), this)
        getCommand("model")!!.setExecutor(modelCommand)
        getCommand("model")!!.tabCompleter = modelCommand
        // Paper processors run before Velocity routes private/staff chat.
        val bridge = PaperChatBridge(this)
        chatBridge = bridge
        bridge.start()
        startStatsPushTask()
        startLoginStreakCache()
        startGlobalChatService()
        startPlayerSettings()
        startBingo()
        startHalloween()
        getCommand("halloween")!!.setExecutor { sender, _, _, _ ->
            if (sender !is org.bukkit.entity.Player) {
                sender.sendMessage("Use /halloween in-game to see your progress.")
            } else {
                val manager = halloweenManager
                if (manager == null)
                    sender.sendMessage(CrabMessages.muted("The Halloween event is currently disabled."))
                else manager.showProgress(sender)
            }
            true
        }
        startSignMarkers()
        startHappyGhastSpeed()
        startViewDistanceManager()
        if (Bukkit.getPluginManager().getPlugin("voicechat") != null) {
            try {
                voicechatRegistration = VoicechatIntegration.register(this)
            } catch (e: LinkageError) {
                logger.warning("Simple Voice Chat present but API classes not usable: ${e.message}")
            }
        }
        logger.info("CrabUtilities enabled. EssentialsX present: ${essentials != null}")
    }

    fun getEssentials(): Plugin? = essentials

    fun isVanished(player: org.bukkit.entity.Player?): Boolean = VanishStatus.isVanished(essentials, player)

    fun onVanishStatusChanged(player: org.bukkit.entity.Player) {
        refreshMentionAutocomplete()
        locatorBarManager?.refresh(player)
    }

    fun refreshMentionAutocomplete() {
        mentionAutocompleteListener?.refreshAll()
    }

    /** The per-player settings mirror, or null before it has started. */
    fun getPlayerSettingsService(): PlayerSettingsService? = playerSettingsService

    fun isBingoMessagesEnabled(playerId: java.util.UUID): Boolean =
        playerSettingsService?.isBingoMessagesEnabled(playerId) ?: true

    /** Used to stage updater replacements with the same filename. */
    fun getPluginJarFile(): File = file

    fun saveModuleConfigValues(values: Map<String, *>) = moduleConfigManager.saveValues(values)

    fun getConfigReloadTargets(): List<String> = moduleConfigManager.reloadTargets()

    fun reloadRuntimeConfig(target: String): List<String> {
        val normalised = target.lowercase(Locale.ROOT)
        moduleConfigManager.reload(normalised)
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
            else -> throw IllegalArgumentException("Unknown configuration reload target: $target")
        }
        return messages
    }

    private fun reloadMediaRuntime(messages: MutableList<String>) {
        messages.add(
            if (MediaFeature.get().refreshConfiguration()) "Media settings and destination policy reloaded."
            else "Media settings reloaded, but the destination policy could not be restarted."
        )
    }

    private fun reloadCoreRuntime(messages: MutableList<String>) {
        stopStatsPushTask()
        startStatsPushTask()
        messages.add("Stats push restarted with current season, Redis, and interval settings.")
        stopLoginStreakCache()
        startLoginStreakCache()
        messages.add("Login streak cache restarted with current Redis settings.")
        nicknameSync?.let {
            it.shutdown()
            it.start()
            messages.add("Nickname Redis sync restarted with current Redis settings.")
        }
        reloadChatRuntime(messages)
        reloadGameplayRuntime(messages)
        updateService?.let {
            it.shutdown()
            if (config.getBoolean("auto-update.enabled", true)) {
                it.start()
                messages.add("Auto-update scheduler restarted with current settings.")
            } else messages.add("Auto-update scheduler stopped because auto-update.enabled=false.")
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
        restrictedAreaListener?.refresh()
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
        messages.add(
            if (JadeBootstrap.isEnabled()) "Jade integration active."
            else "Jade integration inactive (disabled in config)."
        )
        messages.add(
            if (AppleSkinIntegration.isEnabled()) "AppleSkin integration active."
            else "AppleSkin integration inactive (disabled in config)."
        )
        messages.add(
            if (simpleVoiceAnimationsIntegration?.isActive() == true) "Simple Voice Animations integration active."
            else "Simple Voice Animations integration inactive (disabled in config)."
        )
        stopBingo()
        startBingo()
        messages.add(
            if (bingoManager != null) "Weekly bingo tracking restarted."
            else "Weekly bingo tracking inactive (disabled in config)."
        )
        stopHalloween()
        startHalloween()
        messages.add(
            if (halloweenManager != null) "Halloween tracking restarted."
            else "Halloween tracking inactive (disabled in config)."
        )
        stopSignMarkers()
        startSignMarkers()
        messages.add(
            if (signMarkerService != null) "BlueMap sign markers restarted with current settings."
            else "BlueMap sign markers inactive (disabled in config or BlueMap not installed)."
        )
        stopAccurateBlockPlacement()
        startAccurateBlockPlacement()
        messages.add(
            if (accurateBlockPlacementManager?.isActive() == true) "Accurate block placement protocol active."
            else "Accurate block placement protocol inactive (disabled or PacketEvents unavailable)."
        )
        messages.add("Restart required for Xaero settings.")
    }

    private fun reloadTweaksRuntime(messages: MutableList<String>) {
        stopHappyGhastSpeed()
        startHappyGhastSpeed()
        messages.add(
            happyGhastSpeedManager?.let { "Happy ghast ridden speed boost active (x${it.getMultiplier()})." }
                ?: "Happy ghast ridden speed boost inactive (disabled in config)."
        )
        reloadViewDistanceManager()
        messages.add(
            viewDistanceManager?.let {
                "Adaptive view distance ${if (it.isPaused()) "paused" else "active"} (simulation " +
                    "${it.getMinimumSimulationDistance()}–${it.getMaximumSimulationDistance()}, view " +
                    "${it.getMinimumViewDistance()}–${it.getMaximumViewDistance()})."
            } ?: "Adaptive view distance inactive (disabled or invalid config)."
        )
        unlockAllRecipesManager?.let {
            it.refresh()
            messages.add("Recipe unlock cache rebuilt and re-applied to online players (if enabled).")
        }
        customNetherPortalListener?.let {
            it.invalidate()
            messages.add("Custom nether portal settings cache cleared (re-read on next ignite).")
        }
        messages.add("Live-read gameplay tweak settings reloaded.")
    }

    private fun reloadVoicechatRuntime(messages: MutableList<String>) {
        messages.add("Restart required for voice chat, cross-server groups, and Lofi 24/7 CrabFM settings.")
    }

    private fun startUpdateService() {
        if (config.getBoolean("auto-update.enabled", true)) updateService?.start()
    }

    private fun startStatsPushTask() {
        val task = StatsPushTask(this)
        statsPushTask = task
        task.start()
    }

    private fun stopStatsPushTask() {
        statsPushTask?.shutdown()
        statsPushTask = null
    }

    private fun startLoginStreakCache() {
        val cache = LoginStreakCache(this)
        loginStreakCache = cache
        cache.start()
        Bukkit.getPluginManager().registerEvents(cache, this)
        registerLoginStreakExpansion()
    }

    private fun stopLoginStreakCache() {
        loginStreakExpansion?.let {
            try {
                it.unregister()
            } catch (_: Throwable) {}
        }
        loginStreakExpansion = null
        loginStreakCache?.let {
            HandlerList.unregisterAll(it)
            it.shutdown()
        }
        loginStreakCache = null
    }

    private fun registerLoginStreakExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                val expansion = LoginStreakExpansion(this, loginStreakCache!!)
                loginStreakExpansion = expansion
                if (expansion.register()) logger.info("Registered PlaceholderAPI expansion 'crabutilities'")
                else logger.warning("PlaceholderAPI expansion registration returned false")
            } catch (e: NoClassDefFoundError) {
                logger.warning("PlaceholderAPI present but classes not visible: ${e.message}")
            }
        } else logger.info("PlaceholderAPI not detected — streak placeholders disabled.")
    }

    private fun startGlobalChatService() {
        val service = GlobalChatService(this)
        globalChatService = service
        service.start()
        val listener = GlobalChatListener(service)
        globalChatListener = listener
        Bukkit.getPluginManager().registerEvents(listener, this)
    }

    private fun startPlayerSettings() {
        val service = PlayerSettingsService(this)
        playerSettingsService = service
        val phantoms = PhantomManager(this, service)
        phantomManager = phantoms
        val locator = LocatorBarManager(this, service)
        locatorBarManager = locator
        val coordinates = CoordinateHudManager(this, service)
        coordinateHudManager = coordinates
        val dialog = SettingsDialog(service)
        settingsDialog = dialog
        Bukkit.getPluginManager().registerEvents(service, this)
        Bukkit.getPluginManager().registerEvents(phantoms, this)
        Bukkit.getPluginManager().registerEvents(locator, this)
        coordinates.start()
        locator.start()
        service.start()
        phantoms.start()
        val command = SettingsCommand(service, dialog)
        getCommand("settings")?.let {
            it.setExecutor(command)
            it.tabCompleter = command
        }
    }

    private fun stopPlayerSettings() {
        settingsDialog = null
        locatorBarManager?.let { HandlerList.unregisterAll(it) }
        locatorBarManager = null
        phantomManager?.let { HandlerList.unregisterAll(it) }
        phantomManager = null
        coordinateHudManager?.shutdown()
        coordinateHudManager = null
        playerSettingsService?.let {
            HandlerList.unregisterAll(it)
            it.shutdown()
        }
        playerSettingsService = null
    }

    private fun startHalloween() {
        if (!config.getBoolean("halloween.enabled", false)) return
        val manager = HalloweenManager(this)
        halloweenManager = manager
        manager.start()
    }

    private fun stopHalloween() {
        halloweenManager?.shutdown()
        halloweenManager = null
    }

    private fun startBingo() {
        val manager = BingoManager(this)
        manager.start()
        if (config.getBoolean("bingo.enabled", false)) bingoManager = manager
    }

    private fun stopBingo() {
        bingoManager?.shutdown()
        bingoManager = null
    }

    private fun startSignMarkers() {
        if (!config.getBoolean("bluemap.sign-markers.enabled", false)) return
        if (Bukkit.getPluginManager().getPlugin("BlueMap") == null) {
            logger.info("BlueMap not detected — sign markers disabled.")
            return
        }
        // Only resolve BlueMap types after presence detection, including method-ref linkage errors.
        try {
            val service = SignMarkerService(this)
            signMarkerService = service
            service.start()
            logger.info("BlueMap sign markers enabled (keyword: ${service.getKeyword()})")
        } catch (e: LinkageError) {
            signMarkerService = null
            logger.warning("BlueMap present but API classes not usable: ${e.message}")
        }
    }

    private fun stopSignMarkers() {
        signMarkerService?.shutdown()
        signMarkerService = null
    }

    private fun startHappyGhastSpeed() {
        val manager = HappyGhastSpeedManager(this)
        if (!manager.isEnabled()) return
        happyGhastSpeedManager = manager
        Bukkit.getPluginManager().registerEvents(manager, this)
        manager.start()
        logger.info("Happy ghast ridden speed boost enabled (x${manager.getMultiplier()})")
    }

    private fun stopHappyGhastSpeed() {
        happyGhastSpeedManager?.let {
            HandlerList.unregisterAll(it)
            it.shutdown()
        }
        happyGhastSpeedManager = null
    }

    private fun startViewDistanceManager() = startViewDistanceManager(ViewDistanceManager(this))

    private fun startViewDistanceManager(manager: ViewDistanceManager) {
        if (!manager.isEnabled()) return
        viewDistanceManager = manager
        manager.start()
        logger.info(
            "Adaptive view distance enabled (simulation ${manager.getMinimumSimulationDistance()}–" +
                "${manager.getMaximumSimulationDistance()}, view ${manager.getMinimumViewDistance()}–${manager.getMaximumViewDistance()})"
        )
    }

    private fun reloadViewDistanceManager() {
        val wasPaused = viewDistanceManager?.isPaused() == true
        val replacement = ViewDistanceManager(this)
        viewDistanceManager?.shutdown(!replacement.isEnabled())
        viewDistanceManager = null
        startViewDistanceManager(replacement)
        if (wasPaused) viewDistanceManager?.pause()
    }

    private fun stopViewDistanceManager(restoreActualRadius: Boolean) {
        viewDistanceManager?.shutdown(restoreActualRadius)
        viewDistanceManager = null
    }

    private fun startAccurateBlockPlacement() {
        val manager = AccurateBlockPlacementManager(this)
        accurateBlockPlacementManager = manager
        if (manager.start(config.getBoolean("mod-protocols.accurate-block-placement.enabled", false))) {
            logger.info("Accurate block placement enabled (Carpet protocol v2 via PacketEvents).")
        }
    }

    private fun stopAccurateBlockPlacement() {
        accurateBlockPlacementManager?.shutdown()
        accurateBlockPlacementManager = null
    }

    private fun startSimpleVoiceAnimations() {
        if (!config.getBoolean("mod-protocols.simple-voice-animations.enabled", true)) {
            logger.info("Simple Voice Animations integration disabled in config.")
            return
        }
        val integration = SimpleVoiceAnimationsIntegration(this)
        simpleVoiceAnimationsIntegration = integration
        integration.start()
        logger.info("Simple Voice Animations integration enabled.")
    }

    private fun stopSimpleVoiceAnimations() {
        simpleVoiceAnimationsIntegration?.shutdown()
        simpleVoiceAnimationsIntegration = null
    }

    private fun stopGlobalChatService() {
        globalChatListener?.let { HandlerList.unregisterAll(it) }
        globalChatListener = null
        globalChatService?.shutdown()
        globalChatService = null
    }

    override fun onDisable() {
        vanishStatusPublisher?.shutdown()
        vanishStatusPublisher = null
        stopSimpleVoiceAnimations()
        stopAccurateBlockPlacement()
        stopStatsPushTask()
        updateService?.shutdown()
        voicechatRegistration?.let {
            // Voice-bridge cleanup must not abort the rest of shutdown.
            try {
                it.close()
            } catch (e: Exception) {
                logger.warning("Voice bridge shutdown failed: ${e.message}")
            }
        }
        voicechatRegistration = null
        // Stop voice playback before its shared media engine.
        MediaFeature.disable()
        stopLoginStreakCache()
        nicknameSync?.shutdown()
        nicknameSync = null
        chatBridge?.shutdown()
        chatBridge = null
        stopGlobalChatService()
        stopBingo()
        stopHalloween()
        stopPlayerSettings()
        stopSignMarkers()
        stopHappyGhastSpeed()
        stopViewDistanceManager(true)
        JadeBootstrap.disable(this)
        AppleSkinIntegration.disable(this)
    }
}
