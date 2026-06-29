package crabcraft.net.crabUtilities.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
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
import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter;
import crabcraft.net.crabUtilities.velocity.messaging.MessageManager;
import crabcraft.net.crabUtilities.velocity.messaging.MsgCommand;
import crabcraft.net.crabUtilities.velocity.messaging.ReplyCommand;
import crabcraft.net.crabUtilities.velocity.messaging.SocialSpyCommand;
import crabcraft.net.crabUtilities.velocity.staffchat.RedisStaffChat;
import crabcraft.net.crabUtilities.velocity.voicechat.PlayerLocationTracker;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatCommand;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatListener;
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
import java.util.concurrent.ThreadFactory;
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
    private PlayerLocationTracker playerLocationTracker;
    private StaffChatManager staffChatManager;
    private MessageManager messageManager;
    private WebServer webServer;
    private NicknameCache nicknameCache;
    private PendingJoinManager pendingJoinManager;
    private DiscordWebhook discordWebhook;
    private DiscordWebhook staffChatDiscordWebhook;
    private StatsPushSubscriber statsPushSubscriber;
    private PostgresStatsWriter pgWriter;
    private AwardEvaluator awardEvaluator;
    private AwardDbWriter awardDbWriter;
    private AwardQueryService awardQueryService;
    private StatsQueryService statsQueryService;
    private AdvancementDbWriter advancementDbWriter;
    private AdvancementRegistry advancementRegistry;
    private AdvancementQueryService advancementQueryService;
    private VelocityConfig config;
    private UpdateService updateService;
    private AltQueryService altQueryService;
    private LoginStreakService loginStreakService;
    private LoginStreakPublisher loginStreakPublisher;
    private PlayerSettingsRepository playerSettingsRepository;
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
        VelocityConfig config = this.config;
        this.databaseExecutor = createExecutor("CrabUtilities-DB", 4, 256);

        this.nicknameCache = new NicknameCache();
        this.pendingJoinManager = new PendingJoinManager();
        this.discordWebhook = new DiscordWebhook(config.getDiscordWebhookUrl(), logger);
        this.staffChatDiscordWebhook = new DiscordWebhook(config.getStaffChatDiscordWebhookUrl(), logger);

        server.getChannelRegistrar().register(MinecraftChannelIdentifier.from("crabutilities:nicknames"));
        server.getEventManager().register(this, new NicknameListener(this));

        this.pgWriter = new PostgresStatsWriter(
            config.getDbUrl(), config.getDbUsername(), config.getDbPassword(), logger
        );

        AwardSeeder.seedIfEmpty(pgWriter.getDataSource(), logger);
        Map<String, AwardDefinition> awards = AwardLoader.loadAll(pgWriter.getDataSource(), logger);
        logger.info("Loaded {} award definitions from database", awards.size());
        this.awardEvaluator = new AwardEvaluator(awards);
        this.awardDbWriter = new AwardDbWriter(pgWriter.getDataSource(), logger);
        this.awardQueryService = new AwardQueryService(pgWriter.getDataSource(), logger);
        this.statsQueryService = new StatsQueryService(pgWriter.getDataSource(), logger);
        this.advancementDbWriter = new AdvancementDbWriter(pgWriter.getDataSource(), logger);
        this.advancementRegistry = new AdvancementRegistry(logger);
        this.advancementQueryService = new AdvancementQueryService(pgWriter.getDataSource(), logger, advancementRegistry);

        this.altQueryService = new AltQueryService(pgWriter.getDataSource(), logger);

        this.loginStreakService = new LoginStreakService(
                pgWriter.getDataSource(), logger, config.getLoginStreakResetHourUtc());
        this.loginStreakPublisher = new LoginStreakPublisher(this, config);
        this.liteBansInfractionService = new LiteBansInfractionService(logger);
        this.punishmentEventPublisher = new PunishmentEventPublisher(this, config);
        this.punishmentEventPublisher.start();

        this.playerSettingsRepository = new PlayerSettingsRepository(pgWriter.getDataSource(), logger);
        this.playerSettingsService = new PlayerSettingsService(this, playerSettingsRepository, config);
        this.playerSettingsService.start();

        try {
            this.luckPerms = LuckPermsProvider.get();
            logger.info("LuckPerms API connected.");
        } catch (IllegalStateException e) {
            logger.warn("LuckPerms not available — alt whitelist checks disabled.", e);
            this.luckPerms = null;
        }

        this.statsPushSubscriber = new StatsPushSubscriber(this, config, logger);
        this.statsPushSubscriber.start();

        this.webServer = new WebServer(this, config.getApiPort());
        this.webServer.start();

        this.redisStaffChat = new RedisStaffChat(this, config);
        this.redisStaffChat.start();

        this.staffChatManager = new StaffChatManager(this, redisStaffChat,
                staffChatDiscordWebhook, config.getStaffChatDiscordAvatarUrl());

        this.messageManager = new MessageManager(this);

        this.updateService = new UpdateService(this);
        if (config.isUpdateEnabled()) {
            updateService.start();
        }

        StaffChatCommand.register(this);
        StaffChatToggleCommand.register(this);
        MsgCommand.register(this);
        ReplyCommand.register(this);
        SocialSpyCommand.register(this);
        ReloadCommand.register(this);

        server.getEventManager().register(this, new StaffChatListener(this));
        server.getEventManager().register(this, new ConnectionListener(this));

        if (config.isVoicechatCrossServerEnabled()) {
            this.playerLocationTracker = new PlayerLocationTracker(this, config);
            this.playerLocationTracker.start();
            server.getEventManager().register(this, playerLocationTracker);
            logger.info("Voice cross-server location tracker enabled.");
        }

        logger.info("CrabUtilities Velocity enabled.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        synchronized (lifecycleLock) {
            stopRuntimeConsumers();
            shutdownDatabaseExecutor("shutdown");
            stopLoginStreakPublisher();
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
            stopLoginStreakPublisher();

            PostgresStatsWriter oldPgWriter = this.pgWriter;
            PostgresStatsWriter newPgWriter = new PostgresStatsWriter(
                newConfig.getDbUrl(), newConfig.getDbUsername(), newConfig.getDbPassword(), logger
            );

            AwardSeeder.seedIfEmpty(newPgWriter.getDataSource(), logger);
            Map<String, AwardDefinition> awards = AwardLoader.loadAll(newPgWriter.getDataSource(), logger);
            logger.info("Loaded {} award definitions from database", awards.size());

            AdvancementRegistry newAdvancementRegistry = new AdvancementRegistry(logger);

            this.config = newConfig;
            this.pgWriter = newPgWriter;
            this.awardEvaluator = new AwardEvaluator(awards);
            this.awardDbWriter = new AwardDbWriter(newPgWriter.getDataSource(), logger);
            this.awardQueryService = new AwardQueryService(newPgWriter.getDataSource(), logger);
            this.statsQueryService = new StatsQueryService(newPgWriter.getDataSource(), logger);
            this.advancementDbWriter = new AdvancementDbWriter(newPgWriter.getDataSource(), logger);
            this.advancementRegistry = newAdvancementRegistry;
            this.advancementQueryService = new AdvancementQueryService(
                    newPgWriter.getDataSource(), logger, newAdvancementRegistry);
            this.altQueryService = new AltQueryService(newPgWriter.getDataSource(), logger);
            this.loginStreakService = new LoginStreakService(
                    newPgWriter.getDataSource(), logger, newConfig.getLoginStreakResetHourUtc());
            this.liteBansInfractionService = new LiteBansInfractionService(logger);
            this.punishmentEventPublisher = new PunishmentEventPublisher(this, newConfig);
            this.punishmentEventPublisher.start();

            if (oldPgWriter != null) {
                oldPgWriter.close();
            }

            this.loginStreakPublisher = new LoginStreakPublisher(this, newConfig);
            this.playerSettingsRepository = new PlayerSettingsRepository(newPgWriter.getDataSource(), logger);
            this.playerSettingsService = new PlayerSettingsService(this, playerSettingsRepository, newConfig);
            this.playerSettingsService.start();
            this.discordWebhook = new DiscordWebhook(newConfig.getDiscordWebhookUrl(), logger);
            this.staffChatDiscordWebhook = new DiscordWebhook(newConfig.getStaffChatDiscordWebhookUrl(), logger);
            this.databaseExecutor = createExecutor("CrabUtilities-DB", 4, 256);

            this.statsPushSubscriber = new StatsPushSubscriber(this, newConfig, logger);
            this.statsPushSubscriber.start();

            this.webServer = new WebServer(this, newConfig.getApiPort());
            this.webServer.start();

            this.redisStaffChat = new RedisStaffChat(this, newConfig);
            this.redisStaffChat.start();

            if (newConfig.isVoicechatCrossServerEnabled()) {
                this.playerLocationTracker = new PlayerLocationTracker(this, newConfig);
                this.playerLocationTracker.start();
                server.getEventManager().register(this, playerLocationTracker);
            }

            this.staffChatManager = new StaffChatManager(this, redisStaffChat,
                    staffChatDiscordWebhook, newConfig.getStaffChatDiscordAvatarUrl());

            this.updateService = new UpdateService(this);
            if (newConfig.isUpdateEnabled()) {
                updateService.start();
            }

            logger.info("CrabUtilities Velocity reloaded.");
        }
    }

    public ProxyServer getServer() { return server; }
    public Logger getLogger() { return logger; }
    public Path getDataDirectory() { return dataDirectory; }
    public StaffChatManager getStaffChatManager() { return staffChatManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public RedisStaffChat getRedisStaffChat() { return redisStaffChat; }
    public NicknameCache getNicknameCache() { return nicknameCache; }
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

    public void runDatabaseTask(String taskName, Runnable task) {
        ExecutorService executor = databaseExecutor;
        if (executor == null || executor.isShutdown()) {
            logger.warn("Skipping database task {} because the executor is stopped", taskName);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    logger.error("Database task {} failed", taskName, e);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warn("Skipping database task {} because the executor queue is full", taskName);
        }
    }

    private void stopRuntimeConsumers() {
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
    }

    private void stopLoginStreakPublisher() {
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

    private static ExecutorService createExecutor(String name, int threads, int queueSize) {
        ThreadFactory factory = new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger count =
                    new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, name + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(
                threads, threads,
                30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueSize),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
