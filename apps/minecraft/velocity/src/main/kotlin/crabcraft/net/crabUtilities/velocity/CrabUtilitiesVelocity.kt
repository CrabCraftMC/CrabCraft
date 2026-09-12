package crabcraft.net.crabUtilities.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import crabcraft.net.crabUtilities.velocity.api.StatsPushSubscriber
import crabcraft.net.crabUtilities.velocity.api.WebServer
import crabcraft.net.crabUtilities.velocity.awards.AwardDbWriter
import crabcraft.net.crabUtilities.velocity.awards.AwardEvaluator
import crabcraft.net.crabUtilities.velocity.awards.AwardLoader
import crabcraft.net.crabUtilities.velocity.awards.AwardQueryService
import crabcraft.net.crabUtilities.velocity.awards.StatsQueryService
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementDbWriter
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementQueryService
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementRegistry
import crabcraft.net.crabUtilities.velocity.awards.AwardSeeder
import crabcraft.net.crabUtilities.velocity.db.PlayerSettingsRepository
import crabcraft.net.crabUtilities.velocity.db.BingoRepository
import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter
import crabcraft.net.crabUtilities.velocity.messaging.MessageManager
import crabcraft.net.crabUtilities.velocity.messaging.MsgCommand
import crabcraft.net.crabUtilities.velocity.messaging.SocialSpyCommand
import crabcraft.net.crabUtilities.velocity.messaging.VelocityChatBridge
import crabcraft.net.crabUtilities.velocity.staffchat.RedisStaffChat
import crabcraft.net.crabUtilities.velocity.voicechat.CallCommand
import crabcraft.net.crabUtilities.velocity.voicechat.CallManager
import crabcraft.net.crabUtilities.velocity.voicechat.PlayerLocationTracker
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatManager
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatToggleCommand
import crabcraft.net.crabUtilities.velocity.db.AltQueryService
import crabcraft.net.crabUtilities.velocity.db.LoginStreakService
import crabcraft.net.crabUtilities.velocity.litebans.LiteBansInfractionService
import crabcraft.net.crabUtilities.velocity.litebans.PunishmentEventPublisher
import crabcraft.net.crabUtilities.velocity.update.UpdateService
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.slf4j.Logger

import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Plugin(id = "crabutilities", name = "CrabUtilities", version = BuildInfo.VERSION,
    url = "https://www.crabcraft.net", authors = ["Max"], dependencies = [Dependency(id = "litebans", optional = true)])
open class CrabUtilitiesVelocity @Inject constructor(private val server: ProxyServer, private val logger: Logger,
                                                     @DataDirectory private val dataDirectory: Path) {
    private var redisStaffChat: RedisStaffChat? = null
    @Volatile private var callManager: CallManager? = null
    private var playerLocationTracker: PlayerLocationTracker? = null
    private lateinit var staffChatManager: StaffChatManager
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
    private var playerSettingsService: PlayerSettingsService? = null
    private lateinit var liteBansInfractionService: LiteBansInfractionService
    private var punishmentEventPublisher: PunishmentEventPublisher? = null
    private var luckPerms: LuckPerms? = null
    @Volatile private var databaseExecutor: ExecutorService? = null
    private val lifecycleLock = Any()
    @Subscribe
    open fun onProxyInitialize(event: ProxyInitializeEvent) {
        this.config = VelocityConfig.load(dataDirectory, logger)
        this.nicknameCache = NicknameCache()
        this.pendingJoinManager = PendingJoinManager()
        this.messageManager = MessageManager(this)

        this.pgWriter = PostgresStatsWriter(
            config.getDbUrl(), config.getDbUsername(), config.getDbPassword(), logger
        )
        initialiseDatabaseServices(config)

        try {
            this.luckPerms = LuckPermsProvider.get()
            logger.info("LuckPerms API connected.")
        } catch (e: IllegalStateException) {
            logger.warn("LuckPerms not available — alt whitelist checks disabled.", e)
            this.luckPerms = null
        }

        startRuntimeConsumers(config)
        this.chatBridge = VelocityChatBridge(this)
        chatBridge!!.start()


        MsgCommand.register(this)
        StaffChatToggleCommand.register(this)
        SocialSpyCommand.register(this)
        ReloadCommand.register(this)
        CallCommand.register(this)

        this.connectionListener = ConnectionListener(this)
        server.getEventManager().register(this, connectionListener!!)

        if (playerLocationTracker != null) {
            logger.info("Voice cross-server location tracker enabled.")
        }

        logger.info("CrabUtilities Velocity enabled.")
    }

    @Subscribe
    open fun onProxyShutdown(event: ProxyShutdownEvent) {
        synchronized(lifecycleLock) {
            if (chatBridge != null) {
                chatBridge!!.shutdown()
                chatBridge = null
            }
            if (connectionListener != null) {
                connectionListener!!.shutdown()
            }
            stopRuntimeConsumers()
            shutdownDatabaseExecutor("shutdown")
            if (pgWriter != null) {
                pgWriter!!.close()
                pgWriter = null
            }
        }
        logger.info("CrabUtilities Velocity disabled.")
    }

    open fun reload() {
        synchronized(lifecycleLock) {
            val newConfig = VelocityConfig.load(dataDirectory, logger)

            stopRuntimeConsumers()
            shutdownDatabaseExecutor("reload")

            val oldPgWriter = this.pgWriter
            val newPgWriter = PostgresStatsWriter(
                newConfig.getDbUrl(), newConfig.getDbUsername(), newConfig.getDbPassword(), logger
            )

            this.config = newConfig
            this.pgWriter = newPgWriter
            initialiseDatabaseServices(newConfig)

            if (oldPgWriter != null) {
                oldPgWriter.close()
            }

            startRuntimeConsumers(newConfig)

            logger.info("CrabUtilities Velocity reloaded.")
        }
    }

    private fun initialiseDatabaseServices(config: VelocityConfig) {
        val dataSource = pgWriter!!.getDataSource()
        AwardSeeder.seedIfEmpty(dataSource, logger)
        val awards = AwardLoader.loadAll(dataSource, logger)
        logger.info("Loaded {} award definitions from database", awards.size)

        this.awardEvaluator = AwardEvaluator(awards)
        this.awardDbWriter = AwardDbWriter(dataSource, logger)
        this.awardQueryService = AwardQueryService(dataSource, logger)
        this.statsQueryService = StatsQueryService(dataSource, logger)
        this.advancementDbWriter = AdvancementDbWriter(dataSource, logger)
        val advancementRegistry = AdvancementRegistry(logger)
        this.advancementQueryService = AdvancementQueryService(
                dataSource, logger, advancementRegistry)
        this.altQueryService = AltQueryService(dataSource, logger)
        this.loginStreakService = LoginStreakService(
                dataSource, logger,
                config.getLoginStreakResetHourUtc(),
                config.getLoginStreakRequiredPlaySeconds())
        BingoRepository(dataSource, logger)
        this.liteBansInfractionService = LiteBansInfractionService(logger)
    }

    private fun startRuntimeConsumers(config: VelocityConfig) {
        this.databaseExecutor = createDatabaseExecutor()
        this.discordWebhook = DiscordWebhook(config.getDiscordWebhookUrl(), logger)
        val staffChatWebhook =
                DiscordWebhook(config.getStaffChatDiscordWebhookUrl(), logger)

        this.loginStreakPublisher = LoginStreakPublisher(this, config)
        this.punishmentEventPublisher = PunishmentEventPublisher(this, config)
        this.punishmentEventPublisher!!.start()

        val settingsRepository =
                PlayerSettingsRepository(pgWriter!!.getDataSource(), logger)
        this.playerSettingsService = PlayerSettingsService(this, settingsRepository, config)
        this.playerSettingsService!!.start()

        this.nicknameListener = NicknameListener(this, config)
        this.nicknameListener!!.start()
        server.getEventManager().register(this, nicknameListener!!)

        this.statsPushSubscriber = StatsPushSubscriber(this, config, logger)
        this.statsPushSubscriber!!.start()

        this.webServer = WebServer(this, config.getApiPort())
        this.webServer!!.start()

        this.redisStaffChat = RedisStaffChat(this, config)
        this.staffChatManager = StaffChatManager(this, redisStaffChat!!,
                staffChatWebhook, config.getStaffChatDiscordAvatarUrl())
        this.redisStaffChat!!.start()

        this.updateService = UpdateService(this)
        if (config.isUpdateEnabled()) {
            updateService!!.start()
        }

        if (config.isVoicechatCrossServerEnabled()) {
            this.playerLocationTracker = PlayerLocationTracker(this, config)
            this.playerLocationTracker!!.start()
            server.getEventManager().register(this, playerLocationTracker!!)

            this.callManager = CallManager(this, config, playerLocationTracker!!)
            this.callManager!!.start()
            server.getEventManager().register(this, callManager!!)
        }
    }

    open fun getServer(): ProxyServer = server
    open fun getLogger(): Logger = logger
    open fun getDataDirectory(): Path = dataDirectory
    open fun getStaffChatManager(): StaffChatManager = staffChatManager
    open fun getMessageManager(): MessageManager = messageManager
    open fun getChatBridge(): VelocityChatBridge? = chatBridge
    open fun getNicknameCache(): NicknameCache = nicknameCache
    open fun getNicknameListener(): NicknameListener? = nicknameListener
    open fun getPendingJoinManager(): PendingJoinManager = pendingJoinManager
    open fun getDiscordWebhook(): DiscordWebhook = discordWebhook
    open fun getWebServer(): WebServer? = webServer
    open fun getPgWriter(): PostgresStatsWriter? = pgWriter
    open fun getAwardEvaluator(): AwardEvaluator = awardEvaluator
    open fun getAwardDbWriter(): AwardDbWriter = awardDbWriter
    open fun getAwardQueryService(): AwardQueryService = awardQueryService
    open fun getStatsQueryService(): StatsQueryService = statsQueryService
    open fun getAdvancementDbWriter(): AdvancementDbWriter = advancementDbWriter
    open fun getAdvancementQueryService(): AdvancementQueryService = advancementQueryService
    open fun getConfig(): VelocityConfig = config
    open fun getUpdateService(): UpdateService? = updateService
    open fun getAltQueryService(): AltQueryService = altQueryService
    open fun getLoginStreakService(): LoginStreakService = loginStreakService
    open fun getLoginStreakPublisher(): LoginStreakPublisher? = loginStreakPublisher
    open fun getPlayerSettingsService(): PlayerSettingsService? = playerSettingsService
    open fun getLiteBansInfractionService(): LiteBansInfractionService = liteBansInfractionService
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
        } catch (e: RejectedExecutionException) {
            logger.warn("Skipping database task {} because the executor queue is full", taskName)
            return false
        }
    }

    private fun stopRuntimeConsumers() {
        val calls = callManager
        callManager = null
        if (calls != null) {
            calls.shutdown()
            server.getEventManager().unregisterListener(this, calls)
        }
        if (statsPushSubscriber != null) {
            statsPushSubscriber!!.shutdown()
            statsPushSubscriber = null
        }
        if (webServer != null) {
            webServer!!.stop()
            webServer = null
        }
        if (redisStaffChat != null) {
            redisStaffChat!!.shutdown()
            redisStaffChat = null
        }
        if (nicknameListener != null) {
            nicknameListener!!.shutdown()
            server.getEventManager().unregisterListener(this, nicknameListener!!)
            nicknameListener = null
        }
        if (playerLocationTracker != null) {
            playerLocationTracker!!.shutdown()
            server.getEventManager().unregisterListener(this, playerLocationTracker!!)
            playerLocationTracker = null
        }
        if (punishmentEventPublisher != null) {
            punishmentEventPublisher!!.shutdown()
            punishmentEventPublisher = null
        }
        if (updateService != null) {
            updateService!!.shutdown()
            updateService = null
        }
        if (playerSettingsService != null) {
            playerSettingsService!!.shutdown()
            playerSettingsService = null
        }
        if (loginStreakPublisher != null) {
            loginStreakPublisher!!.shutdown()
            loginStreakPublisher = null
        }
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
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Database executor still has running work after {}", reason)
                }
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun createDatabaseExecutor(): ExecutorService {
        return ThreadPoolExecutor(
                4, 4,
                30L, TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>(256),
                Thread.ofPlatform().daemon().name("CrabUtilities-DB-", 1).factory(),
                ThreadPoolExecutor.AbortPolicy())
    }
}
