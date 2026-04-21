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
import crabcraft.net.crabUtilities.velocity.awards.AwardSeeder;
import crabcraft.net.crabUtilities.velocity.db.PostgresStatsWriter;
import crabcraft.net.crabUtilities.velocity.staffchat.RedisStaffChat;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatCommand;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatListener;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatManager;
import crabcraft.net.crabUtilities.velocity.staffchat.StaffChatToggleCommand;
import crabcraft.net.crabUtilities.velocity.update.UpdateService;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Map;

@Plugin(
        id = "crabutilities",
        name = "CrabUtilities",
        version = BuildInfo.VERSION,
        authors = {"CrabCraft"}
)
public class CrabUtilitiesVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private RedisStaffChat redisStaffChat;
    private StaffChatManager staffChatManager;
    private WebServer webServer;
    private NicknameCache nicknameCache;
    private PendingJoinManager pendingJoinManager;
    private DiscordWebhook discordWebhook;
    private JoinedPlayersStore joinedPlayersStore;
    private StatsPushSubscriber statsPushSubscriber;
    private PostgresStatsWriter pgWriter;
    private AwardEvaluator awardEvaluator;
    private AwardDbWriter awardDbWriter;
    private VelocityConfig config;
    private UpdateService updateService;

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
        this.joinedPlayersStore = new JoinedPlayersStore(dataDirectory, logger);

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

        this.statsPushSubscriber = new StatsPushSubscriber(this, config, logger);
        this.statsPushSubscriber.start();

        this.webServer = new WebServer(this, config.getApiPort());
        this.webServer.start();

        this.redisStaffChat = new RedisStaffChat(this, config);
        this.redisStaffChat.start();

        this.staffChatManager = new StaffChatManager(this, redisStaffChat);

        this.updateService = new UpdateService(this);
        if (config.isUpdateEnabled()) {
            updateService.start();
        }

        StaffChatCommand.register(this);
        StaffChatToggleCommand.register(this);
        ReloadCommand.register(this);

        server.getEventManager().register(this, new StaffChatListener(this));
        server.getEventManager().register(this, new ConnectionListener(this));

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

        this.staffChatManager = new StaffChatManager(this, redisStaffChat);
        this.discordWebhook = new DiscordWebhook(config.getDiscordWebhookUrl(), logger);

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
    public RedisStaffChat getRedisStaffChat() { return redisStaffChat; }
    public NicknameCache getNicknameCache() { return nicknameCache; }
    public PendingJoinManager getPendingJoinManager() { return pendingJoinManager; }
    public DiscordWebhook getDiscordWebhook() { return discordWebhook; }
    public JoinedPlayersStore getJoinedPlayersStore() { return joinedPlayersStore; }
    public WebServer getWebServer() { return webServer; }
    public PostgresStatsWriter getPgWriter() { return pgWriter; }
    public AwardEvaluator getAwardEvaluator() { return awardEvaluator; }
    public AwardDbWriter getAwardDbWriter() { return awardDbWriter; }
    public VelocityConfig getConfig() { return config; }
    public UpdateService getUpdateService() { return updateService; }
}
