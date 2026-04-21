package crabcraft.net.crabUtilities.velocity;

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

    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final String redisChannel;
    private final String staffChatFormat;
    private final int apiPort;
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
    private final String currentSeason;
    private final boolean updateEnabled;
    private final long updateCheckIntervalHours;
    private final boolean updateIncludePrereleases;
    private final String updateGithubRepo;
    private final String updateGithubToken;

    private VelocityConfig(String redisHost, int redisPort, String redisPassword,
                           String redisChannel, String staffChatFormat, int apiPort,
                           List<String> ignoredServers, String firstJoinFormat,
                           String discordWebhookUrl, String discordJoinFormat,
                           String discordLeaveFormat, String discordSwapFormat,
                           String discordFirstJoinFormat,
                           String dbUrl, String dbUsername, String dbPassword,
                           String currentSeason,
                           boolean updateEnabled, long updateCheckIntervalHours,
                           boolean updateIncludePrereleases,
                           String updateGithubRepo, String updateGithubToken) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
        this.redisChannel = redisChannel;
        this.staffChatFormat = staffChatFormat;
        this.apiPort = apiPort;
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
        this.currentSeason = currentSeason;
        this.updateEnabled = updateEnabled;
        this.updateCheckIntervalHours = updateCheckIntervalHours;
        this.updateIncludePrereleases = updateIncludePrereleases;
        this.updateGithubRepo = updateGithubRepo;
        this.updateGithubToken = updateGithubToken;
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
                    loader.save(root);
                }
            }

            ConfigurationNode redis = root.node("redis");
            String host = redis.node("host").getString("localhost");
            int port = redis.node("port").getInt(6379);
            String password = redis.node("password").getString("");
            String channel = redis.node("channel").getString("crabutilities:staffchat");

            String format = root.node("staff-chat", "format").getString(DEFAULT_FORMAT);

            int apiPort = root.node("api", "port").getInt(8080);

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
            String currentSeason = database.node("current-season").getString("s1");

            ConfigurationNode update = root.node("auto-update");
            boolean updateEnabled = update.node("enabled").getBoolean(true);
            long updateInterval = update.node("check-interval-hours").getLong(6L);
            boolean updateIncludePre = update.node("include-prereleases").getBoolean(false);
            String updateRepo = update.node("github-repo").getString("CrabCraftMC/CrabCraft");
            String updateToken = update.node("github-token").getString("");

            return new VelocityConfig(host, port, password, channel, format, apiPort,
                    ignoredServers, firstJoinFormat, discordWebhookUrl, discordJoinFormat,
                    discordLeaveFormat, discordSwapFormat, discordFirstJoinFormat,
                    dbUrl, dbUsername, dbPassword, currentSeason,
                    updateEnabled, updateInterval, updateIncludePre, updateRepo, updateToken);
        } catch (IOException e) {
            logger.error("Failed to load config, using defaults", e);
            return new VelocityConfig("localhost", 6379, "", "crabutilities:staffchat", DEFAULT_FORMAT, 8080,
                    List.of(), "<yellow><name> joined the game for the first time</yellow>",
                    "", "{name} joined the game", "{name} left the game", "{name} swapped to the {server} server",
                    "{name} joined the game for the first time!",
                    "jdbc:postgresql://localhost:5432/crabcraft", "crabcraft", "", "s1",
                    true, 6L, false, "CrabCraftMC/CrabCraft", "");
        }
    }

    public String getRedisHost() { return redisHost; }
    public int getRedisPort() { return redisPort; }
    public String getRedisPassword() { return redisPassword; }
    public String getRedisChannel() { return redisChannel; }
    public String getStaffChatFormat() { return staffChatFormat; }
    public int getApiPort() { return apiPort; }
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
    public String getCurrentSeason() { return currentSeason; }
    public boolean isUpdateEnabled() { return updateEnabled; }
    public long getUpdateCheckIntervalHours() { return updateCheckIntervalHours; }
    public boolean isUpdateIncludePrereleases() { return updateIncludePrereleases; }
    public String getUpdateGithubRepo() { return updateGithubRepo; }
    public String getUpdateGithubToken() { return updateGithubToken; }
}
