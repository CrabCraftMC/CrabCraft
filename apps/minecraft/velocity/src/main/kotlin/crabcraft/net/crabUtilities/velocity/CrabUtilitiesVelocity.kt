package crabcraft.net.crabUtilities.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementDbWriter
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementQueryService
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementRegistry
import crabcraft.net.crabUtilities.velocity.api.StatsPushSubscriber
import crabcraft.net.crabUtilities.velocity.api.WebServer
import crabcraft.net.crabUtilities.velocity.awards.AwardDbWriter
import crabcraft.net.crabUtilities.velocity.awards.AwardEvaluator
import crabcraft.net.crabUtilities.velocity.awards.AwardLoader
import crabcraft.net.crabUtilities.velocity.awards.AwardQueryService
import crabcraft.net.crabUtilities.velocity.awards.AwardSeeder
import crabcraft.net.crabUtilities.velocity.awards.StatsQueryService
import crabcraft.net.crabUtilities.velocity.db.AltQueryService
import crabcraft.net.crabUtilities.velocity.db.BingoRepository
import crabcraft.net.crabUtilities.velocity.db.HalloweenRepository
import crabcraft.net.crabUtilities.velocity.db.LoginStreakService
import crabcraft.net.crabUtilities.velocity.db.PlayerSettingsRepository
import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter
import crabcraft.net.crabUtilities.velocity.litebans.LiteBansInfractionService
import crabcraft.net.crabUtilities.velocity.litebans.PunishmentEventPublisher
import crabcraft.net.crabUtilities.velocity.messaging.MessageManager
import crabcraft.net.crabUtilities.velocity.messaging.MsgCommand
import crabcraft.net.crabUtilities.velocity.messaging.SocialSpyCommand
import crabcraft.net.crabUtilities.velocity.messaging.VelocityChatBridge
import crabcraft.net.crabUtilities.velocity.staffchat.RedisStaffChat
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatManager
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatToggleCommand
import crabcraft.net.crabUtilities.velocity.update.UpdateService
import crabcraft.net.crabUtilities.velocity.voicechat.CallCommand
import crabcraft.net.crabUtilities.velocity.voicechat.CallManager
import crabcraft.net.crabUtilities.velocity.voicechat.PlayerLocationTracker
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.slf4j.Logger

@Plugin(
    id = "crabutilities",
    name = "CrabUtilities",
    version = BuildInfo.VERSION,
    url = "https://www.crabcraft.net",
    authors = ["Max"],
    dependencies = [Dependency(id = "litebans", optional = true)],
)
open class CrabUtilitiesVelocity
@Inject
constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @DataDirectory private val dataDirectory: Path,
) {
    private var redisStaffChat: RedisStaffChat? = null
    @Volatile private var callManager: CallManager? = null
    private var playerLocationTracker: PlayerLocationTracker? = null
    private var staffChatManager: StaffChatManager? = null
    private lateinit var messageManager: MessageManager
    private var chatBridge: VelocityChatBridge? = null
    private var webServer: WebServer? = null
    private lateinit var nicknameCache: NicknameCache
    private var nicknameListener: NicknameListener? = null
    private lateinit var pendingJoinManager: PendingJoinManager
    private lateinit var discordWebhook: DiscordWebhook
    private var statsPushSubscriber: StatsPushSubscriber? = null
    private var pgWriter: PostgresStatsWriter? = null
    private lateinit var awardEvaluator: AwardEvaluator
    private lateinit var awardDbWriter: AwardDbWriter
    private lateinit var awardQueryService: AwardQueryService
    private lateinit var statsQueryService: StatsQueryService
    private lateinit var advancementDbWriter: AdvancementDbWriter
    private lateinit var advancementQueryService: AdvancementQueryService
    private lateinit var config: VelocityConfig
    private var updateService: UpdateService? = null
    private lateinit var altQueryService: AltQueryService
    private lateinit var loginStreakService: LoginStreakService
    private var loginStreakPublisher: LoginStreakPublisher? = null
    private var connectionListener: ConnectionListener? = null
    private var vanishManager: VanishManager? = null
    private var playerSettingsService: PlayerSettingsService? = null
    private var liteBansInfractionService: LiteBansInfractionService? = null
    private var punishmentEventPublisher: PunishmentEventPublisher? = null
    private var luckPerms: LuckPerms? = null
    @Volatile private var databaseExecutor: ExecutorService? = null
    private val lifecycleLock = Any()

    @Subscribe
    open fun onProxyInitialize(event: ProxyInitializeEvent) {
        config = VelocityConfig.load(dataDirectory, logger)
        nicknameCache = NicknameCache()
        pendingJoinManager = PendingJoinManager()
        messageManager = MessageManager(this)
        vanishManager = VanishManager(this)
        vanishManager!!.start()
        pgWriter = PostgresStatsWriter(config.getDbUrl(), config.getDbUsername(), config.getDbPassword(), logger)
        initialiseDatabaseServices(config)
        try {
            luckPerms = LuckPermsProvider.get()
            logger.info("LuckPerms API connected.")
        } catch (e: IllegalStateException) {
            logger.warn("LuckPerms not available — alt whitelist checks disabled.", e)
            luckPerms = null
        }
        startRuntimeConsumers(config)
        chatBridge = VelocityChatBridge(this)
        chatBridge!!.start()
        MsgCommand.register(this)
        StaffChatToggleCommand.register(this)
        SocialSpyCommand.register(this)
        ReloadCommand.register(this)
        CallCommand.register(this)
        connectionListener = ConnectionListener(this)
        server.eventManager.register(this, connectionListener!!)
        if (playerLocationTracker != null) logger.info("Voice cross-server location tracker enabled.")
        logger.info("CrabUtilities Velocity enabled.")
    }

    @Subscribe
    open fun onProxyShutdown(event: ProxyShutdownEvent) {
        synchronized(lifecycleLock) {
            chatBridge?.shutdown()
            chatBridge = null
            connectionListener?.shutdown()
            stopRuntimeConsumers()
            vanishManager?.shutdown()
            vanishManager = null
            shutdownDatabaseExecutor("shutdown")
            pgWriter?.close()
            pgWriter = null
        }
        logger.info("CrabUtilities Velocity disabled.")
    }

    open fun reload() {
        synchronized(lifecycleLock) {
            val newConfig = VelocityConfig.load(dataDirectory, logger)
            stopRuntimeConsumers()
            shutdownDatabaseExecutor("reload")
            val oldPgWriter = pgWriter
            val newPgWriter =
                PostgresStatsWriter(newConfig.getDbUrl(), newConfig.getDbUsername(), newConfig.getDbPassword(), logger)
            config = newConfig
            pgWriter = newPgWriter
            initialiseDatabaseServices(newConfig)
            oldPgWriter?.close()
            startRuntimeConsumers(newConfig)
            logger.info("CrabUtilities Velocity reloaded.")
        }
    }

    private fun initialiseDatabaseServices(config: VelocityConfig) {
        val dataSource = pgWriter!!.getDataSource()
        AwardSeeder.seedIfEmpty(dataSource, logger)
        val awards = AwardLoader.loadAll(dataSource, logger)
        logger.info("Loaded {} award definitions from database", awards.size)
        awardEvaluator = AwardEvaluator(awards)
        awardDbWriter = AwardDbWriter(dataSource, logger)
        awardQueryService = AwardQueryService(dataSource, logger)
        statsQueryService = StatsQueryService(dataSource, logger)
        advancementDbWriter = AdvancementDbWriter(dataSource, logger)
        val advancementRegistry = AdvancementRegistry(logger)
        advancementQueryService = AdvancementQueryService(dataSource, logger, advancementRegistry)
        altQueryService = AltQueryService(dataSource, logger)
        loginStreakService =
            LoginStreakService(
                dataSource,
                logger,
                config.getLoginStreakResetHourUtc(),
                config.getLoginStreakRequiredPlaySeconds(),
            )
        BingoRepository(dataSource, logger)
        HalloweenRepository(dataSource, logger)
        liteBansInfractionService = LiteBansInfractionService(logger)
    }

    private fun startRuntimeConsumers(config: VelocityConfig) {
        databaseExecutor = createDatabaseExecutor()
        discordWebhook = DiscordWebhook(config.getDiscordWebhookUrl(), logger)
        val staffChatWebhook = DiscordWebhook(config.getStaffChatDiscordWebhookUrl(), logger)
        loginStreakPublisher = LoginStreakPublisher(this, config)
        punishmentEventPublisher = PunishmentEventPublisher(this, config)
        punishmentEventPublisher!!.start()
        val settingsRepository = PlayerSettingsRepository(pgWriter!!.getDataSource(), logger)
        playerSettingsService = PlayerSettingsService(this, settingsRepository, config)
        playerSettingsService!!.start()
        nicknameListener = NicknameListener(this, config)
        nicknameListener!!.start()
        server.eventManager.register(this, nicknameListener!!)
        statsPushSubscriber = StatsPushSubscriber(this, config, logger)
        statsPushSubscriber!!.start()
        webServer = WebServer(this, config.getApiPort())
        webServer!!.start()
        val redis = RedisStaffChat(this, config)
        redisStaffChat = redis
        staffChatManager = StaffChatManager(this, redis, staffChatWebhook, config.getStaffChatDiscordAvatarUrl())
        redis.start()
        updateService = UpdateService(this)
        if (config.isUpdateEnabled()) updateService!!.start()
        if (config.isVoicechatCrossServerEnabled()) {
            val tracker = PlayerLocationTracker(this, config)
            playerLocationTracker = tracker
            tracker.start()
            server.eventManager.register(this, tracker)
            val calls = CallManager(this, config, tracker)
            callManager = calls
            calls.start()
            server.eventManager.register(this, calls)
        }
    }

    open fun getServer() = server

    open fun getLogger() = logger

    open fun getDataDirectory() = dataDirectory

    open fun getStaffChatManager(): StaffChatManager? = staffChatManager

    open fun getMessageManager(): MessageManager? = if (::messageManager.isInitialized) messageManager else null

    open fun getChatBridge(): VelocityChatBridge? = chatBridge

    open fun getNicknameCache() = nicknameCache

    open fun getNicknameListener(): NicknameListener? = nicknameListener

    open fun getPendingJoinManager() = pendingJoinManager

    open fun getDiscordWebhook() = discordWebhook

    open fun getWebServer(): WebServer? = webServer

    open fun getPgWriter(): PostgresStatsWriter? = pgWriter

    open fun getAwardEvaluator(): AwardEvaluator? = if (::awardEvaluator.isInitialized) awardEvaluator else null

    open fun getAwardDbWriter(): AwardDbWriter? = if (::awardDbWriter.isInitialized) awardDbWriter else null

    open fun getAwardQueryService(): AwardQueryService? =
        if (::awardQueryService.isInitialized) awardQueryService else null

    open fun getStatsQueryService() = statsQueryService

    open fun getAdvancementDbWriter(): AdvancementDbWriter? =
        if (::advancementDbWriter.isInitialized) advancementDbWriter else null

    open fun getAdvancementQueryService() = advancementQueryService

    open fun getConfig() = config

    open fun getUpdateService(): UpdateService? = updateService

    open fun getAltQueryService(): AltQueryService? = if (::altQueryService.isInitialized) altQueryService else null

    open fun getLoginStreakService(): LoginStreakService? =
        if (::loginStreakService.isInitialized) loginStreakService else null

    open fun getLoginStreakPublisher(): LoginStreakPublisher? = loginStreakPublisher

    open fun getPlayerSettingsService(): PlayerSettingsService? = playerSettingsService

    open fun getVanishManager(): VanishManager = vanishManager!!

    open fun getLiteBansInfractionService(): LiteBansInfractionService? = liteBansInfractionService

    open fun getLuckPerms(): LuckPerms? = luckPerms

    open fun getCallManager(): CallManager? = callManager

    open fun runDatabaseTask(taskName: String, task: Runnable): Boolean {
        val executor = databaseExecutor
        if (executor == null || executor.isShutdown) {
            logger.warn("Skipping database task {} because the executor is stopped", taskName)
            return false
        }
        try {
            executor.execute {
                try {
                    task.run()
                } catch (e: Exception) {
                    logger.error("Database task {} failed", taskName, e)
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            logger.warn("Skipping database task {} because the executor queue is full", taskName)
            return false
        }
    }

    private fun stopRuntimeConsumers() {
        val calls = callManager
        callManager = null
        if (calls != null) {
            calls.shutdown()
            server.eventManager.unregisterListener(this, calls)
        }
        statsPushSubscriber?.shutdown()
        statsPushSubscriber = null
        webServer?.stop()
        webServer = null
        redisStaffChat?.shutdown()
        redisStaffChat = null
        nicknameListener?.let {
            it.shutdown()
            server.eventManager.unregisterListener(this, it)
        }
        nicknameListener = null
        playerLocationTracker?.let {
            it.shutdown()
            server.eventManager.unregisterListener(this, it)
        }
        playerLocationTracker = null
        punishmentEventPublisher?.shutdown()
        punishmentEventPublisher = null
        updateService?.shutdown()
        updateService = null
        playerSettingsService?.shutdown()
        playerSettingsService = null
        loginStreakPublisher?.shutdown()
        loginStreakPublisher = null
    }

    private fun shutdownDatabaseExecutor(reason: String) {
        val executor = databaseExecutor
        databaseExecutor = null
        if (executor == null) return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warn("Database executor did not stop cleanly during {}; interrupting queued work", reason)
                executor.shutdownNow()
                if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                    logger.warn("Database executor still has running work after {}", reason)
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private fun createDatabaseExecutor(): ExecutorService =
            ThreadPoolExecutor(
                4,
                4,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(256),
                Thread.ofPlatform().daemon().name("CrabUtilities-DB-", 1).factory(),
                ThreadPoolExecutor.AbortPolicy(),
            )
    }
}
