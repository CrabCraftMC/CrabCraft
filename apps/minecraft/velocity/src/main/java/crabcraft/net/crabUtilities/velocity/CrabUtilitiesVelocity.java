package crabcraft.net.crabUtilities.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import crabcraft.net.crabUtilities.velocity.api.StatsPushSubscriber;
import crabcraft.net.crabUtilities.velocity.api.WebServer;
import crabcraft.net.crabUtilities.velocity.awards.AwardDbWriter;
import crabcraft.net.crabUtilities.velocity.awards.AwardDefinition;
import crabcraft.net.crabUtilities.velocity.awards.AwardEvaluator;
import crabcraft.net.crabUtilities.velocity.awards.AwardLoader;
import crabcraft.net.crabUtilities.velocity.awards.AwardQueryService;
import crabcraft.net.crabUtilities.velocity.awards.StatsQueryService;
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementDbWriter;
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementQueryService;
import crabcraft.net.crabUtilities.velocity.advancements.AdvancementRegistry;
import crabcraft.net.crabUtilities.velocity.awards.AwardSeeder;
import crabcraft.net.crabUtilities.velocity.db.PlayerSettingsRepository;
import crabcraft.net.crabUtilities.velocity.db.BingoRepository;
import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter;
import crabcraft.net.crabUtilities.velocity.messaging.MessageManager;
import crabcraft.net.crabUtilities.velocity.messaging.SocialSpyCommand;
import crabcraft.net.crabUtilities.velocity.messaging.VelocityChatBridge;
import crabcraft.net.crabUtilities.velocity.staffchat.RedisStaffChat;
import crabcraft.net.crabUtilities.velocity.voicechat.CallCommand;
import crabcraft.net.crabUtilities.velocity.voicechat.CallManager;
import crabcraft.net.crabUtilities.velocity.voicechat.PlayerLocationTracker;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatManager;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatToggleCommand;
import crabcraft.net.crabUtilities.velocity.db.AltQueryService;
import crabcraft.net.crabUtilities.velocity.db.LoginStreakService;
import crabcraft.net.crabUtilities.velocity.litebans.LiteBansInfractionService;
import crabcraft.net.crabUtilities.velocity.litebans.PunishmentEventPublisher;
import crabcraft.net.crabUtilities.velocity.update.UpdateService;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "crabutilities",
        name = "CrabUtilities",
        version = BuildInfo.VERSION,
        url = "https://www.crabcraft.net",
        authors = {"Max"},
        dependencies = {@Dependency(id = "litebans", optional = true)}
)
public class CrabUtilitiesVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private RedisStaffChat redisStaffChat;
    private volatile CallManager callManager;
    private PlayerLocationTracker playerLocationTracker;
    private StaffChatManager staffChatManager;
    private MessageManager messageManager;
    private VelocityChatBridge chatBridge;
    private WebServer webServer;
    private NicknameCache nicknameCache;
    private NicknameListener nicknameListener;
    private PendingJoinManager pendingJoinManager;
    private DiscordWebhook discordWebhook;
    private StatsPushSubscriber statsPushSubscriber;
    private PostgresStatsWriter pgWriter;
    private AwardEvaluator awardEvaluator;
    private AwardDbWriter awardDbWriter;
    private AwardQueryService awardQueryService;
    private StatsQueryService statsQueryService;
    private AdvancementDbWriter advancementDbWriter;
    private AdvancementQueryService advancementQueryService;
    private VelocityConfig config;
    private UpdateService updateService;
    private AltQueryService altQueryService;
    private LoginStreakService loginStreakService;
    private LoginStreakPublisher loginStreakPublisher;
    private ConnectionListener connectionListener;
    private PlayerSettingsService playerSettingsService;
    private LiteBansInfractionService liteBansInfractionService;
    private PunishmentEventPublisher punishmentEventPublisher;
    private LuckPerms luckPerms;
    private volatile ExecutorService databaseExecutor;
    private final Object lifecycleLock = new Object();

    @Inject
    public CrabUtilitiesVelocity(ProxyServer server, Logger logger,
                                  @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.config = VelocityConfig.load(dataDirectory, logger);
        this.nicknameCache = new NicknameCache();
        this.pendingJoinManager = new PendingJoinManager();
        this.messageManager = new MessageManager(this);

        this.pgWriter = new PostgresStatsWriter(
            config.getDbUrl(), config.getDbUsername(), config.getDbPassword(), logger
        );
        initialiseDatabaseServices(config);

        try {
            this.luckPerms = LuckPermsProvider.get();
            logger.info("LuckPerms API connected.");
        } catch (IllegalStateException e) {
            logger.warn("LuckPerms not available — alt whitelist checks disabled.", e);
            this.luckPerms = null;
        }

        startRuntimeConsumers(config);
        this.chatBridge = new VelocityChatBridge(this);
        chatBridge.start();

        StaffChatToggleCommand.register(this);
        SocialSpyCommand.register(this);
        ReloadCommand.register(this);
        CallCommand.register(this);

        this.connectionListener = new ConnectionListener(this);
        server.getEventManager().register(this, connectionListener);

        if (playerLocationTracker != null) {
            logger.info("Voice cross-server location tracker enabled.");
        }

        logger.info("CrabUtilities Velocity enabled.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        synchronized (lifecycleLock) {
            if (chatBridge != null) {
                chatBridge.shutdown();
                chatBridge = null;
            }
            if (connectionListener != null) {
                connectionListener.shutdown();
            }
            stopRuntimeConsumers();
            shutdownDatabaseExecutor("shutdown");
            if (pgWriter != null) {
                pgWriter.close();
                pgWriter = null;
            }
        }
        logger.info("CrabUtilities Velocity disabled.");
    }

    public void reload() {
        synchronized (lifecycleLock) {
            VelocityConfig newConfig = VelocityConfig.load(dataDirectory, logger);

            stopRuntimeConsumers();
            shutdownDatabaseExecutor("reload");

            PostgresStatsWriter oldPgWriter = this.pgWriter;
            PostgresStatsWriter newPgWriter = new PostgresStatsWriter(
                newConfig.getDbUrl(), newConfig.getDbUsername(), newConfig.getDbPassword(), logger
            );

            this.config = newConfig;
            this.pgWriter = newPgWriter;
            initialiseDatabaseServices(newConfig);

            if (oldPgWriter != null) {
                oldPgWriter.close();
            }

            startRuntimeConsumers(newConfig);

            logger.info("CrabUtilities Velocity reloaded.");
        }
    }

    private void initialiseDatabaseServices(VelocityConfig config) {
        var dataSource = pgWriter.getDataSource();
        AwardSeeder.seedIfEmpty(dataSource, logger);
        Map<String, AwardDefinition> awards = AwardLoader.loadAll(dataSource, logger);
        logger.info("Loaded {} award definitions from database", awards.size());

        this.awardEvaluator = new AwardEvaluator(awards);
        this.awardDbWriter = new AwardDbWriter(dataSource, logger);
        this.awardQueryService = new AwardQueryService(dataSource, logger);
        this.statsQueryService = new StatsQueryService(dataSource, logger);
        this.advancementDbWriter = new AdvancementDbWriter(dataSource, logger);
        AdvancementRegistry advancementRegistry = new AdvancementRegistry(logger);
        this.advancementQueryService = new AdvancementQueryService(
                dataSource, logger, advancementRegistry);
        this.altQueryService = new AltQueryService(dataSource, logger);
        this.loginStreakService = new LoginStreakService(
                dataSource, logger,
                config.getLoginStreakResetHourUtc(),
                config.getLoginStreakRequiredPlaySeconds());
        new BingoRepository(dataSource, logger);
        this.liteBansInfractionService = new LiteBansInfractionService(logger);
    }

    private void startRuntimeConsumers(VelocityConfig config) {
        this.databaseExecutor = createDatabaseExecutor();
        this.discordWebhook = new DiscordWebhook(config.getDiscordWebhookUrl(), logger);
        DiscordWebhook staffChatWebhook =
                new DiscordWebhook(config.getStaffChatDiscordWebhookUrl(), logger);

        this.loginStreakPublisher = new LoginStreakPublisher(this, config);
        this.punishmentEventPublisher = new PunishmentEventPublisher(this, config);
        this.punishmentEventPublisher.start();

        PlayerSettingsRepository settingsRepository =
                new PlayerSettingsRepository(pgWriter.getDataSource(), logger);
        this.playerSettingsService = new PlayerSettingsService(this, settingsRepository, config);
        this.playerSettingsService.start();

        this.nicknameListener = new NicknameListener(this, config);
        this.nicknameListener.start();
        server.getEventManager().register(this, nicknameListener);

        this.statsPushSubscriber = new StatsPushSubscriber(this, config, logger);
        this.statsPushSubscriber.start();

        this.webServer = new WebServer(this, config.getApiPort());
        this.webServer.start();

        this.redisStaffChat = new RedisStaffChat(this, config);
        this.staffChatManager = new StaffChatManager(this, redisStaffChat,
                staffChatWebhook, config.getStaffChatDiscordAvatarUrl());
        this.redisStaffChat.start();

        this.updateService = new UpdateService(this);
        if (config.isUpdateEnabled()) {
            updateService.start();
        }

        if (config.isVoicechatCrossServerEnabled()) {
            this.playerLocationTracker = new PlayerLocationTracker(this, config);
            this.playerLocationTracker.start();
            server.getEventManager().register(this, playerLocationTracker);

            this.callManager = new CallManager(this, config, playerLocationTracker);
            this.callManager.start();
            server.getEventManager().register(this, callManager);
        }
    }

    public ProxyServer getServer() { return server; }
    public Logger getLogger() { return logger; }
    public Path getDataDirectory() { return dataDirectory; }
    public StaffChatManager getStaffChatManager() { return staffChatManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public VelocityChatBridge getChatBridge() { return chatBridge; }
    public NicknameCache getNicknameCache() { return nicknameCache; }
    public NicknameListener getNicknameListener() { return nicknameListener; }
    public PendingJoinManager getPendingJoinManager() { return pendingJoinManager; }
    public DiscordWebhook getDiscordWebhook() { return discordWebhook; }
    public WebServer getWebServer() { return webServer; }
    public PostgresStatsWriter getPgWriter() { return pgWriter; }
    public AwardEvaluator getAwardEvaluator() { return awardEvaluator; }
    public AwardDbWriter getAwardDbWriter() { return awardDbWriter; }
    public AwardQueryService getAwardQueryService() { return awardQueryService; }
    public StatsQueryService getStatsQueryService() { return statsQueryService; }
    public AdvancementDbWriter getAdvancementDbWriter() { return advancementDbWriter; }
    public AdvancementQueryService getAdvancementQueryService() { return advancementQueryService; }
    public VelocityConfig getConfig() { return config; }
    public UpdateService getUpdateService() { return updateService; }
    public AltQueryService getAltQueryService() { return altQueryService; }
    public LoginStreakService getLoginStreakService() { return loginStreakService; }
    public LoginStreakPublisher getLoginStreakPublisher() { return loginStreakPublisher; }
    public PlayerSettingsService getPlayerSettingsService() { return playerSettingsService; }
    public LiteBansInfractionService getLiteBansInfractionService() { return liteBansInfractionService; }
    public LuckPerms getLuckPerms() { return luckPerms; }
    public CallManager getCallManager() { return callManager; }

    public boolean runDatabaseTask(String taskName, Runnable task) {
        ExecutorService executor = databaseExecutor;
        if (executor == null || executor.isShutdown()) {
            logger.warn("Skipping database task {} because the executor is stopped", taskName);
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    logger.error("Database task {} failed", taskName, e);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            logger.warn("Skipping database task {} because the executor queue is full", taskName);
            return false;
        }
    }

    private void stopRuntimeConsumers() {
        CallManager calls = callManager;
        callManager = null;
        if (calls != null) {
            calls.shutdown();
            server.getEventManager().unregisterListener(this, calls);
        }
        if (statsPushSubscriber != null) {
            statsPushSubscriber.shutdown();
            statsPushSubscriber = null;
        }
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (redisStaffChat != null) {
            redisStaffChat.shutdown();
            redisStaffChat = null;
        }
        if (nicknameListener != null) {
            nicknameListener.shutdown();
            server.getEventManager().unregisterListener(this, nicknameListener);
            nicknameListener = null;
        }
        if (playerLocationTracker != null) {
            playerLocationTracker.shutdown();
            server.getEventManager().unregisterListener(this, playerLocationTracker);
            playerLocationTracker = null;
        }
        if (punishmentEventPublisher != null) {
            punishmentEventPublisher.shutdown();
            punishmentEventPublisher = null;
        }
        if (updateService != null) {
            updateService.shutdown();
            updateService = null;
        }
        if (playerSettingsService != null) {
            playerSettingsService.shutdown();
            playerSettingsService = null;
        }
        if (loginStreakPublisher != null) {
            loginStreakPublisher.shutdown();
            loginStreakPublisher = null;
        }
    }

    private void shutdownDatabaseExecutor(String reason) {
        ExecutorService executor = databaseExecutor;
        databaseExecutor = null;
        if (executor == null) return;

        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warn("Database executor did not stop cleanly during {}; interrupting queued work", reason);
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Database executor still has running work after {}", reason);
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService createDatabaseExecutor() {
        return new ThreadPoolExecutor(
                4, 4,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                Thread.ofPlatform().daemon().name("CrabUtilities-DB-", 1).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
