package crabcraft.net.crabUtilities.velocity;

import crabcraft.net.crabUtilities.velocity.db.LoginStreakService;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class VelocityConfig {

    private static final String DEFAULT_FORMAT =
            "<dark_gray>[<aqua>SC</aqua>]</dark_gray> <gray><sender></gray> <dark_gray>></dark_gray> <white><message></white>";
    private static final String DEFAULT_MSG_OUTGOING =
            "<gold>(to <target>) <white><message>";
    private static final String DEFAULT_MSG_INCOMING =
            "<gold>(from <sender>) <white><message>";
    private static final String DEFAULT_MSG_SPY =
            "<gray>[SPY] (<sender> → <target>) <white><message>";
    private static final String DEFAULT_MSG_PLAYER_NOT_FOUND =
            "<red>Player not found or not online.";
    private static final String DEFAULT_MSG_NO_REPLY_TARGET =
            "<red>You have no one to reply to.";
    private static final String DEFAULT_MSG_SELF =
            "<red>You can't message yourself.";

    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final String redisChannel;
    private final String redisPunishmentStream;
    private final long redisPunishmentWatchIntervalSeconds;
    private final String staffChatFormat;
    private final String staffChatDiscordWebhookUrl;
    private final String staffChatDiscordAvatarUrl;
    private final String msgOutgoingFormat;
    private final String msgIncomingFormat;
    private final String msgSpyFormat;
    private final String msgPlayerNotFound;
    private final String msgNoReplyTarget;
    private final String msgSelfError;
    private final boolean msgIncomingSoundEnabled;
    private final String msgIncomingSoundKey;
    private final float msgIncomingSoundVolume;
    private final float msgIncomingSoundPitch;
    private final int apiPort;
    private final boolean publicChatEnabled;
    private final String publicChatStream;
    private final int publicChatReplayMessages;
    private final int publicChatMaxConnections;
    private final int publicChatMaxConnectionsPerIp;
    private final List<String> publicChatAllowedOrigins;
    private final List<String> ignoredServers;
    private final String firstJoinFormat;
    private final String discordWebhookUrl;
    private final String discordJoinFormat;
    private final String discordLeaveFormat;
    private final String discordSwapFormat;
    private final String discordFirstJoinFormat;
    private final String dbUrl;
    private final String dbUsername;
    private final String dbPassword;
    private final boolean updateEnabled;
    private final long updateCheckIntervalHours;
    private final boolean updateIncludePrereleases;
    private final String updateGithubRepo;
    private final String updateGithubToken;
    private final boolean voicechatCrossServerEnabled;
    private final long voicechatPlayerHomeTtlSeconds;
    private final int loginStreakResetHourUtc;
    private final int loginStreakRequiredPlaySeconds;
    private final boolean restrictedAreaEnabled;
    private final String restrictedAreaBypassPermission;

    private VelocityConfig(String redisHost, int redisPort, String redisPassword,
                           String redisChannel, String redisPunishmentStream,
                           long redisPunishmentWatchIntervalSeconds,
                           String staffChatFormat,
                           String staffChatDiscordWebhookUrl, String staffChatDiscordAvatarUrl,
                           String msgOutgoingFormat, String msgIncomingFormat,
                           String msgSpyFormat,
                           String msgPlayerNotFound, String msgNoReplyTarget,
                           String msgSelfError,
                           boolean msgIncomingSoundEnabled, String msgIncomingSoundKey,
                           float msgIncomingSoundVolume, float msgIncomingSoundPitch,
                           int apiPort,
                           boolean publicChatEnabled, String publicChatStream,
                           int publicChatReplayMessages, int publicChatMaxConnections,
                           int publicChatMaxConnectionsPerIp,
                           List<String> publicChatAllowedOrigins,
                           List<String> ignoredServers, String firstJoinFormat,
                           String discordWebhookUrl, String discordJoinFormat,
                           String discordLeaveFormat, String discordSwapFormat,
                           String discordFirstJoinFormat,
                           String dbUrl, String dbUsername, String dbPassword,
                           boolean updateEnabled, long updateCheckIntervalHours,
                           boolean updateIncludePrereleases,
                           String updateGithubRepo, String updateGithubToken,
                           boolean voicechatCrossServerEnabled,
                           long voicechatPlayerHomeTtlSeconds,
                           int loginStreakResetHourUtc,
                           int loginStreakRequiredPlaySeconds,
                           boolean restrictedAreaEnabled,
                           String restrictedAreaBypassPermission) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
        this.redisChannel = redisChannel;
        this.redisPunishmentStream = redisPunishmentStream;
        this.redisPunishmentWatchIntervalSeconds = redisPunishmentWatchIntervalSeconds;
        this.staffChatFormat = staffChatFormat;
        this.staffChatDiscordWebhookUrl = staffChatDiscordWebhookUrl;
        this.staffChatDiscordAvatarUrl = staffChatDiscordAvatarUrl;
        this.msgOutgoingFormat = msgOutgoingFormat;
        this.msgIncomingFormat = msgIncomingFormat;
        this.msgSpyFormat = msgSpyFormat;
        this.msgPlayerNotFound = msgPlayerNotFound;
        this.msgNoReplyTarget = msgNoReplyTarget;
        this.msgSelfError = msgSelfError;
        this.msgIncomingSoundEnabled = msgIncomingSoundEnabled;
        this.msgIncomingSoundKey = msgIncomingSoundKey;
        this.msgIncomingSoundVolume = msgIncomingSoundVolume;
        this.msgIncomingSoundPitch = msgIncomingSoundPitch;
        this.apiPort = apiPort;
        this.publicChatEnabled = publicChatEnabled;
        this.publicChatStream = publicChatStream;
        this.publicChatReplayMessages = publicChatReplayMessages;
        this.publicChatMaxConnections = publicChatMaxConnections;
        this.publicChatMaxConnectionsPerIp = publicChatMaxConnectionsPerIp;
        this.publicChatAllowedOrigins = publicChatAllowedOrigins;
        this.ignoredServers = ignoredServers;
        this.firstJoinFormat = firstJoinFormat;
        this.discordWebhookUrl = discordWebhookUrl;
        this.discordJoinFormat = discordJoinFormat;
        this.discordLeaveFormat = discordLeaveFormat;
        this.discordSwapFormat = discordSwapFormat;
        this.discordFirstJoinFormat = discordFirstJoinFormat;
        this.dbUrl = dbUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.updateEnabled = updateEnabled;
        this.updateCheckIntervalHours = updateCheckIntervalHours;
        this.updateIncludePrereleases = updateIncludePrereleases;
        this.updateGithubRepo = updateGithubRepo;
        this.updateGithubToken = updateGithubToken;
        this.voicechatCrossServerEnabled = voicechatCrossServerEnabled;
        this.voicechatPlayerHomeTtlSeconds = voicechatPlayerHomeTtlSeconds;
        this.loginStreakResetHourUtc = loginStreakResetHourUtc;
        this.loginStreakRequiredPlaySeconds = loginStreakRequiredPlaySeconds;
        this.restrictedAreaEnabled = restrictedAreaEnabled;
        this.restrictedAreaBypassPermission = restrictedAreaBypassPermission;
    }

    public static VelocityConfig load(Path dataDirectory, Logger logger) {
        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve("config.yml");

            if (!Files.exists(configPath)) {
                try (InputStream in = VelocityConfig.class.getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    }
                }
            }

            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(configPath)
                    .nodeStyle(NodeStyle.BLOCK)
                    .build();
            ConfigurationNode root = loader.load();

            // Merge missing keys from bundled defaults into the user's config
            try (InputStream defaultIn = VelocityConfig.class.getResourceAsStream("/config.yml")) {
                if (defaultIn != null) {
                    ConfigurationNode defaults = YamlConfigurationLoader.builder()
                            .source(() -> new java.io.BufferedReader(new java.io.InputStreamReader(defaultIn)))
                            .build()
                            .load();
                    root.mergeFrom(defaults);
                    // Older configs carried login-streaks.buffer-hours (now replaced
                    // by reset-hour-utc); mergeFrom adds the new key but never prunes
                    // the old one, so drop it explicitly to avoid a dead setting.
                    root.node("login-streaks").removeChild("buffer-hours");
                    loader.save(root);
                }
            }

            ConfigurationNode redis = root.node("redis");
            String host = redis.node("host").getString("localhost");
            int port = redis.node("port").getInt(6379);
            String password = redis.node("password").getString("");
            String channel = redis.node("channel").getString("crabutilities:staffchat");
            String punishmentStream = redis.node("punishment-stream").getString("crabcraft:punishments");
            long punishmentWatchIntervalSeconds = Math.max(1L,
                    redis.node("punishment-watch-interval-seconds").getLong(10L));

            String format = root.node("staff-chat", "format").getString(DEFAULT_FORMAT);
            ConfigurationNode staffChatDiscord = root.node("staff-chat", "discord");
            String staffChatDiscordWebhookUrl = staffChatDiscord.node("webhook-url").getString("");
            String staffChatDiscordAvatarUrl = staffChatDiscord.node("avatar-url")
                    .getString("https://mc-heads.net/head/{uuid}");

            ConfigurationNode msgNode = root.node("private-messages");
            String msgOutgoing = msgNode.node("outgoing-format").getString(DEFAULT_MSG_OUTGOING);
            String msgIncoming = msgNode.node("incoming-format").getString(DEFAULT_MSG_INCOMING);
            String msgSpy = msgNode.node("spy-format").getString(DEFAULT_MSG_SPY);
            String msgNotFound = msgNode.node("player-not-found").getString(DEFAULT_MSG_PLAYER_NOT_FOUND);
            String msgNoReply = msgNode.node("no-reply-target").getString(DEFAULT_MSG_NO_REPLY_TARGET);
            String msgSelf = msgNode.node("self-error").getString(DEFAULT_MSG_SELF);

            ConfigurationNode incomingSound = msgNode.node("incoming-sound");
            boolean soundEnabled = incomingSound.node("enabled").getBoolean(true);
            String soundKey = incomingSound.node("sound").getString("minecraft:entity.experience_orb.pickup");
            float soundVolume = (float) incomingSound.node("volume").getDouble(1.0);
            float soundPitch = (float) incomingSound.node("pitch").getDouble(1.0);

            int apiPort = root.node("api", "port").getInt(8080);

            ConfigurationNode publicChat = root.node("public-chat");
            boolean publicChatEnabled = publicChat.node("enabled").getBoolean(false);
            String publicChatStream = publicChat.node("stream")
                    .getString("crabcraft:public-chat");
            if (publicChatStream == null || publicChatStream.isBlank()) {
                publicChatStream = "crabcraft:public-chat";
            }
            int publicChatReplayMessages = Math.max(1,
                    Math.min(20, publicChat.node("replay-messages").getInt(6)));
            int publicChatMaxConnections = Math.max(1,
                    publicChat.node("max-connections").getInt(64));
            int publicChatMaxConnectionsPerIp = Math.max(1,
                    publicChat.node("max-connections-per-ip").getInt(2));
            List<String> publicChatAllowedOrigins = new ArrayList<>();
            ConfigurationNode originsNode = publicChat.node("allowed-origins");
            if (!originsNode.virtual()) {
                for (ConfigurationNode child : originsNode.childrenList()) {
                    String value = child.getString();
                    if (value != null && !value.isBlank()) {
                        publicChatAllowedOrigins.add(value);
                    }
                }
            }

            List<String> ignoredServers = new ArrayList<>();
            ConfigurationNode ignoredNode = root.node("join-leave-messages", "ignored-servers");
            if (!ignoredNode.virtual()) {
                for (ConfigurationNode child : ignoredNode.childrenList()) {
                    String val = child.getString();
                    if (val != null) ignoredServers.add(val.toLowerCase());
                }
            }

            String firstJoinFormat = root.node("join-leave-messages", "first-join")
                    .getString("<yellow><name> joined the game for the first time</yellow>");

            ConfigurationNode discord = root.node("join-leave-messages", "discord");
            String discordWebhookUrl = discord.node("webhook-url").getString("");
            String discordJoinFormat = discord.node("join").getString("{name} joined the game");
            String discordLeaveFormat = discord.node("leave").getString("{name} left the game");
            String discordSwapFormat = discord.node("swap").getString("{name} swapped to the {server} server");
            String discordFirstJoinFormat = discord.node("first-join").getString("{name} joined the game for the first time!");

            ConfigurationNode database = root.node("database");
            String dbUrl = database.node("url").getString("jdbc:postgresql://localhost:5432/crabcraft");
            String dbUsername = database.node("username").getString("crabcraft");
            String dbPassword = database.node("password").getString("");
            ConfigurationNode update = root.node("auto-update");
            boolean updateEnabled = update.node("enabled").getBoolean(true);
            long updateInterval = update.node("check-interval-hours").getLong(6L);
            boolean updateIncludePre = update.node("include-prereleases").getBoolean(false);
            String updateRepo = update.node("github-repo").getString("CrabCraftMC/CrabCraft");
            String updateToken = update.node("github-token").getString("");

            ConfigurationNode voicechat = root.node("voicechat", "cross-server");
            boolean vcEnabled = voicechat.node("enabled").getBoolean(true);
            long vcHomeTtl = voicechat.node("player-home-ttl-seconds").getLong(300L);

            int streakResetHour = root.node("login-streaks", "reset-hour-utc")
                    .getInt(LoginStreakService.DEFAULT_RESET_HOUR_UTC);
            int streakRequiredMinutes = root.node("login-streaks", "required-play-minutes")
                    .getInt(LoginStreakService.DEFAULT_REQUIRED_PLAY_MINUTES);

            ConfigurationNode restrictedArea = root.node("restricted-area");
            boolean restrictedAreaEnabled = restrictedArea.node("enabled").getBoolean(false);
            String restrictedAreaBypassPermission = restrictedArea.node("bypass-permission")
                    .getString("crabutilities.restricted-area.bypass");
            if (restrictedAreaBypassPermission == null
                    || restrictedAreaBypassPermission.isBlank()) {
                restrictedAreaBypassPermission = "crabutilities.restricted-area.bypass";
            }

            return new VelocityConfig(host, port, password, channel,
                    punishmentStream, punishmentWatchIntervalSeconds, format,
                    staffChatDiscordWebhookUrl, staffChatDiscordAvatarUrl,
                    msgOutgoing, msgIncoming, msgSpy, msgNotFound, msgNoReply, msgSelf,
                    soundEnabled, soundKey, soundVolume, soundPitch, apiPort,
                    publicChatEnabled, publicChatStream, publicChatReplayMessages,
                    publicChatMaxConnections, publicChatMaxConnectionsPerIp,
                    List.copyOf(publicChatAllowedOrigins),
                    ignoredServers, firstJoinFormat, discordWebhookUrl, discordJoinFormat,
                    discordLeaveFormat, discordSwapFormat, discordFirstJoinFormat,
                    dbUrl, dbUsername, dbPassword,
                    updateEnabled, updateInterval, updateIncludePre, updateRepo, updateToken,
                    vcEnabled, vcHomeTtl, streakResetHour,
                    LoginStreakService.minutesToSeconds(streakRequiredMinutes),
                    restrictedAreaEnabled, restrictedAreaBypassPermission);
        } catch (IOException e) {
            logger.error("Failed to load config, using defaults", e);
            return new VelocityConfig("localhost", 6379, "", "crabutilities:staffchat",
                    "crabcraft:punishments", 10L, DEFAULT_FORMAT,
                    "", "https://mc-heads.net/head/{uuid}",
                    DEFAULT_MSG_OUTGOING, DEFAULT_MSG_INCOMING, DEFAULT_MSG_SPY,
                    DEFAULT_MSG_PLAYER_NOT_FOUND, DEFAULT_MSG_NO_REPLY_TARGET, DEFAULT_MSG_SELF,
                    true, "minecraft:entity.experience_orb.pickup", 1.0f, 1.0f, 8080,
                    false, "crabcraft:public-chat", 6, 64, 2,
                    List.of("https://crabcraft.net", "https://www.crabcraft.net"),
                    List.of(), "<yellow><name> joined the game for the first time</yellow>",
                    "", "{name} joined the game", "{name} left the game", "{name} swapped to the {server} server",
                    "{name} joined the game for the first time!",
                    "jdbc:postgresql://localhost:5432/crabcraft", "crabcraft", "",
                    true, 6L, false, "CrabCraftMC/CrabCraft", "",
                    true, 300L, LoginStreakService.DEFAULT_RESET_HOUR_UTC,
                    LoginStreakService.minutesToSeconds(LoginStreakService.DEFAULT_REQUIRED_PLAY_MINUTES),
                    false, "crabutilities.restricted-area.bypass");
        }
    }

    public String getRedisHost() { return redisHost; }
    public int getRedisPort() { return redisPort; }
    public String getRedisPassword() { return redisPassword; }
    public String getRedisChannel() { return redisChannel; }
    public String getRedisPunishmentStream() { return redisPunishmentStream; }
    public long getRedisPunishmentWatchIntervalSeconds() { return redisPunishmentWatchIntervalSeconds; }
    public String getStaffChatFormat() { return staffChatFormat; }
    public String getStaffChatDiscordWebhookUrl() { return staffChatDiscordWebhookUrl; }
    public String getStaffChatDiscordAvatarUrl() { return staffChatDiscordAvatarUrl; }
    public String getMsgOutgoingFormat() { return msgOutgoingFormat; }
    public String getMsgIncomingFormat() { return msgIncomingFormat; }
    public String getMsgSpyFormat() { return msgSpyFormat; }
    public String getMsgPlayerNotFound() { return msgPlayerNotFound; }
    public String getMsgNoReplyTarget() { return msgNoReplyTarget; }
    public String getMsgSelfError() { return msgSelfError; }
    public boolean isMsgIncomingSoundEnabled() { return msgIncomingSoundEnabled; }
    public String getMsgIncomingSoundKey() { return msgIncomingSoundKey; }
    public float getMsgIncomingSoundVolume() { return msgIncomingSoundVolume; }
    public float getMsgIncomingSoundPitch() { return msgIncomingSoundPitch; }
    public int getApiPort() { return apiPort; }
    public boolean isPublicChatEnabled() { return publicChatEnabled; }
    public String getPublicChatStream() { return publicChatStream; }
    public int getPublicChatReplayMessages() { return publicChatReplayMessages; }
    public int getPublicChatMaxConnections() { return publicChatMaxConnections; }
    public int getPublicChatMaxConnectionsPerIp() { return publicChatMaxConnectionsPerIp; }
    public List<String> getPublicChatAllowedOrigins() { return publicChatAllowedOrigins; }
    public List<String> getIgnoredServers() { return ignoredServers; }
    public String getFirstJoinFormat() { return firstJoinFormat; }
    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public String getDiscordJoinFormat() { return discordJoinFormat; }
    public String getDiscordLeaveFormat() { return discordLeaveFormat; }
    public String getDiscordSwapFormat() { return discordSwapFormat; }
    public String getDiscordFirstJoinFormat() { return discordFirstJoinFormat; }
    public String getDbUrl() { return dbUrl; }
    public String getDbUsername() { return dbUsername; }
    public String getDbPassword() { return dbPassword; }
    public boolean isUpdateEnabled() { return updateEnabled; }
    public long getUpdateCheckIntervalHours() { return updateCheckIntervalHours; }
    public boolean isUpdateIncludePrereleases() { return updateIncludePrereleases; }
    public String getUpdateGithubRepo() { return updateGithubRepo; }
    public String getUpdateGithubToken() { return updateGithubToken; }
    public boolean isVoicechatCrossServerEnabled() { return voicechatCrossServerEnabled; }
    public long getVoicechatPlayerHomeTtlSeconds() { return voicechatPlayerHomeTtlSeconds; }
    public int getLoginStreakResetHourUtc() { return loginStreakResetHourUtc; }
    public int getLoginStreakRequiredPlaySeconds() { return loginStreakRequiredPlaySeconds; }
    public boolean isRestrictedAreaEnabled() { return restrictedAreaEnabled; }
    public String getRestrictedAreaBypassPermission() { return restrictedAreaBypassPermission; }
}
