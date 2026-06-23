package crabcraft.net.crabUtilities.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
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
import crabcraft.net.crabUtilities.velocity.update.UpdateService;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Map;

@Plugin(
        id = "crabutilities",
        name = "CrabUtilities",
        version = BuildInfo.VERSION,
        url = "https://www.crabcraft.net",
        authors = {"Max"}
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
    private LuckPerms luckPerms;

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
        if (webServer != null) {
            webServer.stop();
        }
        if (statsPushSubscriber != null) {
            statsPushSubscriber.shutdown();
        }
        if (redisStaffChat != null) {
            redisStaffChat.shutdown();
        }
        if (playerLocationTracker != null) {
            playerLocationTracker.shutdown();
        }
        if (loginStreakPublisher != null) {
            loginStreakPublisher.shutdown();
        }
        if (pgWriter != null) {
            pgWriter.close();
        }
        if (updateService != null) {
            updateService.shutdown();
        }
        logger.info("CrabUtilities Velocity disabled.");
    }

    public void reload() {
        this.config = VelocityConfig.load(dataDirectory, logger);
        VelocityConfig config = this.config;

        if (pgWriter != null) {
            pgWriter.close();
        }
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

        if (loginStreakService != null) {
            this.loginStreakService.setResetHourUtc(config.getLoginStreakResetHourUtc());
        } else {
            this.loginStreakService = new LoginStreakService(
                    pgWriter.getDataSource(), logger, config.getLoginStreakResetHourUtc());
        }
        if (loginStreakPublisher != null) {
            loginStreakPublisher.shutdown();
        }
        this.loginStreakPublisher = new LoginStreakPublisher(this, config);

        if (statsPushSubscriber != null) {
            statsPushSubscriber.shutdown();
        }
        this.statsPushSubscriber = new StatsPushSubscriber(this, config, logger);
        this.statsPushSubscriber.start();

        if (webServer != null) {
            webServer.stop();
        }
        this.webServer = new WebServer(this, config.getApiPort());
        this.webServer.start();

        if (redisStaffChat != null) {
            redisStaffChat.shutdown();
        }
        this.redisStaffChat = new RedisStaffChat(this, config);
        this.redisStaffChat.start();

        if (playerLocationTracker != null) {
            playerLocationTracker.shutdown();
            this.playerLocationTracker = null;
        }
        if (config.isVoicechatCrossServerEnabled()) {
            this.playerLocationTracker = new PlayerLocationTracker(this, config);
            this.playerLocationTracker.start();
            server.getEventManager().register(this, playerLocationTracker);
        }

        this.discordWebhook = new DiscordWebhook(config.getDiscordWebhookUrl(), logger);
        this.staffChatDiscordWebhook = new DiscordWebhook(config.getStaffChatDiscordWebhookUrl(), logger);
        this.staffChatManager = new StaffChatManager(this, redisStaffChat,
                staffChatDiscordWebhook, config.getStaffChatDiscordAvatarUrl());

        if (updateService != null) {
            updateService.shutdown();
        }
        this.updateService = new UpdateService(this);
        if (config.isUpdateEnabled()) {
            updateService.start();
        }

        logger.info("CrabUtilities Velocity reloaded.");
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
    public LuckPerms getLuckPerms() { return luckPerms; }
}
