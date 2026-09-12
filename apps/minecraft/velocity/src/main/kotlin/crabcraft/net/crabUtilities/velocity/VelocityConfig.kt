package crabcraft.net.crabUtilities.velocity

import crabcraft.net.crabUtilities.velocity.db.LoginStreakService
import org.slf4j.Logger
import org.spongepowered.configurate.yaml.NodeStyle
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList

open class VelocityConfig private constructor(
    private val redisHost: String,
    private val redisPort: Int,
    private val redisPassword: String,
    private val redisChannel: String,
    private val redisPunishmentStream: String,
    private val redisPunishmentWatchIntervalSeconds: Long,
    private val staffChatFormat: String,
    private val staffChatDiscordWebhookUrl: String,
    private val staffChatDiscordAvatarUrl: String,
    private val msgOutgoingFormat: String,
    private val msgIncomingFormat: String,
    private val msgSpyFormat: String,
    private val msgPlayerNotFound: String,
    private val msgNoReplyTarget: String,
    private val msgSelfError: String,
    private val msgIncomingSoundEnabled: Boolean,
    private val msgIncomingSoundKey: String,
    private val msgIncomingSoundVolume: Float,
    private val msgIncomingSoundPitch: Float,
    private val apiPort: Int,
    private val publicChatEnabled: Boolean,
    private val publicChatStream: String,
    private val publicChatReplayMessages: Int,
    private val publicChatMaxConnections: Int,
    private val publicChatMaxConnectionsPerIp: Int,
    private val publicChatAllowedOrigins: List<String>,
    private val ignoredServers: List<String>,
    private val firstJoinFormat: String,
    private val discordWebhookUrl: String,
    private val discordJoinFormat: String,
    private val discordLeaveFormat: String,
    private val discordSwapFormat: String,
    private val discordFirstJoinFormat: String,
    private val dbUrl: String,
    private val dbUsername: String,
    private val dbPassword: String,
    private val updateEnabled: Boolean,
    private val updateCheckIntervalHours: Long,
    private val updateIncludePrereleases: Boolean,
    private val updateGithubRepo: String,
    private val updateGithubToken: String,
    private val voicechatCrossServerEnabled: Boolean,
    private val voicechatPlayerHomeTtlSeconds: Long,
    private val loginStreakResetHourUtc: Int,
    private val loginStreakRequiredPlaySeconds: Int
) {
    fun getRedisHost(): String = redisHost
    fun getRedisPort(): Int = redisPort
    fun getRedisPassword(): String = redisPassword
    fun getRedisChannel(): String = redisChannel
    fun getRedisPunishmentStream(): String = redisPunishmentStream
    fun getRedisPunishmentWatchIntervalSeconds(): Long = redisPunishmentWatchIntervalSeconds
    fun getStaffChatFormat(): String = staffChatFormat
    fun getStaffChatDiscordWebhookUrl(): String = staffChatDiscordWebhookUrl
    fun getStaffChatDiscordAvatarUrl(): String = staffChatDiscordAvatarUrl
    fun getMsgOutgoingFormat(): String = msgOutgoingFormat
    fun getMsgIncomingFormat(): String = msgIncomingFormat
    fun getMsgSpyFormat(): String = msgSpyFormat
    fun getMsgPlayerNotFound(): String = msgPlayerNotFound
    fun getMsgNoReplyTarget(): String = msgNoReplyTarget
    fun getMsgSelfError(): String = msgSelfError
    fun isMsgIncomingSoundEnabled(): Boolean = msgIncomingSoundEnabled
    fun getMsgIncomingSoundKey(): String = msgIncomingSoundKey
    fun getMsgIncomingSoundVolume(): Float = msgIncomingSoundVolume
    fun getMsgIncomingSoundPitch(): Float = msgIncomingSoundPitch
    fun getApiPort(): Int = apiPort
    fun isPublicChatEnabled(): Boolean = publicChatEnabled
    fun getPublicChatStream(): String = publicChatStream
    fun getPublicChatReplayMessages(): Int = publicChatReplayMessages
    fun getPublicChatMaxConnections(): Int = publicChatMaxConnections
    fun getPublicChatMaxConnectionsPerIp(): Int = publicChatMaxConnectionsPerIp
    fun getPublicChatAllowedOrigins(): List<String> = publicChatAllowedOrigins
    fun getIgnoredServers(): List<String> = ignoredServers
    fun getFirstJoinFormat(): String = firstJoinFormat
    fun getDiscordWebhookUrl(): String = discordWebhookUrl
    fun getDiscordJoinFormat(): String = discordJoinFormat
    fun getDiscordLeaveFormat(): String = discordLeaveFormat
    fun getDiscordSwapFormat(): String = discordSwapFormat
    fun getDiscordFirstJoinFormat(): String = discordFirstJoinFormat
    fun getDbUrl(): String = dbUrl
    fun getDbUsername(): String = dbUsername
    fun getDbPassword(): String = dbPassword
    fun isUpdateEnabled(): Boolean = updateEnabled
    fun getUpdateCheckIntervalHours(): Long = updateCheckIntervalHours
    fun isUpdateIncludePrereleases(): Boolean = updateIncludePrereleases
    fun getUpdateGithubRepo(): String = updateGithubRepo
    fun getUpdateGithubToken(): String = updateGithubToken
    fun isVoicechatCrossServerEnabled(): Boolean = voicechatCrossServerEnabled
    fun getVoicechatPlayerHomeTtlSeconds(): Long = voicechatPlayerHomeTtlSeconds
    fun getLoginStreakResetHourUtc(): Int = loginStreakResetHourUtc
    fun getLoginStreakRequiredPlaySeconds(): Int = loginStreakRequiredPlaySeconds

    companion object {
        private const val DEFAULT_FORMAT =
                "<dark_gray>[<aqua>SC</aqua>]</dark_gray> <gray><sender></gray> <dark_gray>></dark_gray> <white><message></white>"
        private const val DEFAULT_MSG_OUTGOING =
                "<gold>(to <target>) <white><message>"
        private const val DEFAULT_MSG_INCOMING =
                "<gold>(from <sender>) <white><message>"
        private const val DEFAULT_MSG_SPY =
                "<gray>[SPY] (<sender> → <target>) <white><message>"
        private const val DEFAULT_MSG_PLAYER_NOT_FOUND =
                "<red>Player not found or not online."
        private const val DEFAULT_MSG_NO_REPLY_TARGET =
                "<red>You have no one to reply to."
        private const val DEFAULT_MSG_SELF =
                "<red>You can't message yourself."

        @JvmStatic fun load(dataDirectory: Path, logger: Logger): VelocityConfig {
            try {
                Files.createDirectories(dataDirectory)
                val configPath = dataDirectory.resolve("config.yml")

                if (!Files.exists(configPath)) {
                    VelocityConfig::class.java.getResourceAsStream("/config.yml").use { input ->
                        if (input != null) {
                            Files.copy(input, configPath)
                        }
                    }
                }

                val loader = YamlConfigurationLoader.builder()
                        .path(configPath)
                        .nodeStyle(NodeStyle.BLOCK)
                        .build()
                val root = loader.load()

                // Merge missing keys from bundled defaults into the user's config
                VelocityConfig::class.java.getResourceAsStream("/config.yml").use { defaultIn ->
                    if (defaultIn != null) {
                        val defaults = YamlConfigurationLoader.builder()
                                .source { java.io.BufferedReader(java.io.InputStreamReader(defaultIn)) }
                                .build()
                                .load()
                        root.mergeFrom(defaults)
                        // Older configs carried login-streaks.buffer-hours (now replaced
                        // by reset-hour-utc); mergeFrom adds the new key but never prunes
                        // the old one, so drop it explicitly to avoid a dead setting.
                        root.node("login-streaks").removeChild("buffer-hours")
                        root.removeChild("restricted-area")
                        loader.save(root)
                    }
                }

                val redis = root.node("redis")
                val host = redis.node("host").getString("localhost")!!
                val port = redis.node("port").getInt(6379)
                val password = redis.node("password").getString("")!!
                val channel = redis.node("channel").getString("crabutilities:staffchat")!!
                val punishmentStream = redis.node("punishment-stream").getString("crabcraft:punishments")!!
                val punishmentWatchIntervalSeconds = Math.max(1L,
                        redis.node("punishment-watch-interval-seconds").getLong(10L))

                val format = root.node("staff-chat", "format").getString(DEFAULT_FORMAT)!!
                val staffChatDiscord = root.node("staff-chat", "discord")
                val staffChatDiscordWebhookUrl = staffChatDiscord.node("webhook-url").getString("")!!
                val staffChatDiscordAvatarUrl = staffChatDiscord.node("avatar-url")
                        .getString("https://mc-heads.net/head/{uuid}")!!

                val msgNode = root.node("private-messages")
                val msgOutgoing = msgNode.node("outgoing-format").getString(DEFAULT_MSG_OUTGOING)!!
                val msgIncoming = msgNode.node("incoming-format").getString(DEFAULT_MSG_INCOMING)!!
                val msgSpy = msgNode.node("spy-format").getString(DEFAULT_MSG_SPY)!!
                val msgNotFound = msgNode.node("player-not-found").getString(DEFAULT_MSG_PLAYER_NOT_FOUND)!!
                val msgNoReply = msgNode.node("no-reply-target").getString(DEFAULT_MSG_NO_REPLY_TARGET)!!
                val msgSelf = msgNode.node("self-error").getString(DEFAULT_MSG_SELF)!!

                val incomingSound = msgNode.node("incoming-sound")
                val soundEnabled = incomingSound.node("enabled").getBoolean(true)
                val soundKey = incomingSound.node("sound").getString("minecraft:entity.experience_orb.pickup")!!
                val soundVolume = incomingSound.node("volume").getDouble(1.0).toFloat()
                val soundPitch = incomingSound.node("pitch").getDouble(1.0).toFloat()

                val apiPort = root.node("api", "port").getInt(8080)

                val publicChat = root.node("public-chat")
                val publicChatEnabled = publicChat.node("enabled").getBoolean(false)
                var publicChatStream = publicChat.node("stream")
                        .getString("crabcraft:public-chat")!!
                if (publicChatStream == null || publicChatStream.isBlank()) {
                    publicChatStream = "crabcraft:public-chat"
                }
                val publicChatReplayMessages = Math.max(1,
                        Math.min(20, publicChat.node("replay-messages").getInt(6)))
                val publicChatMaxConnections = Math.max(1,
                        publicChat.node("max-connections").getInt(64))
                val publicChatMaxConnectionsPerIp = Math.max(1,
                        publicChat.node("max-connections-per-ip").getInt(2))
                val publicChatAllowedOrigins = ArrayList<String>()
                val originsNode = publicChat.node("allowed-origins")
                if (!originsNode.virtual()) {
                    for (child in originsNode.childrenList()) {
                        val value = child.getString()
                        if (value != null && !value.isBlank()) {
                            publicChatAllowedOrigins.add(value)
                        }
                    }
                }

                val ignoredServers = ArrayList<String>()
                val ignoredNode = root.node("join-leave-messages", "ignored-servers")
                if (!ignoredNode.virtual()) {
                    for (child in ignoredNode.childrenList()) {
                        val value = child.getString()
                        if (value != null) ignoredServers.add(value.lowercase(java.util.Locale.getDefault()))
                    }
                }

                val firstJoinFormat = root.node("join-leave-messages", "first-join")
                        .getString("<yellow><name> joined the game for the first time</yellow>")!!

                val discord = root.node("join-leave-messages", "discord")
                val discordWebhookUrl = discord.node("webhook-url").getString("")!!
                val discordJoinFormat = discord.node("join").getString("{name} joined the game")!!
                val discordLeaveFormat = discord.node("leave").getString("{name} left the game")!!
                val discordSwapFormat = discord.node("swap").getString("{name} swapped to the {server} server")!!
                val discordFirstJoinFormat = discord.node("first-join").getString("{name} joined the game for the first time!")!!

                val database = root.node("database")
                val dbUrl = database.node("url").getString("jdbc:postgresql://localhost:5432/crabcraft")!!
                val dbUsername = database.node("username").getString("crabcraft")!!
                val dbPassword = database.node("password").getString("")!!
                val update = root.node("auto-update")
                val updateEnabled = update.node("enabled").getBoolean(true)
                val updateInterval = update.node("check-interval-hours").getLong(6L)
                val updateIncludePre = update.node("include-prereleases").getBoolean(false)
                val updateRepo = update.node("github-repo").getString("CrabCraftMC/CrabCraft")!!
                val updateToken = update.node("github-token").getString("")!!

                val voicechat = root.node("voicechat", "cross-server")
                val vcEnabled = voicechat.node("enabled").getBoolean(true)
                val vcHomeTtl = voicechat.node("player-home-ttl-seconds").getLong(300L)

                val streakResetHour = root.node("login-streaks", "reset-hour-utc")
                        .getInt(LoginStreakService.DEFAULT_RESET_HOUR_UTC)
                val streakRequiredMinutes = root.node("login-streaks", "required-play-minutes")
                        .getInt(LoginStreakService.DEFAULT_REQUIRED_PLAY_MINUTES)

                return VelocityConfig(host, port, password, channel,
                        punishmentStream, punishmentWatchIntervalSeconds, format,
                        staffChatDiscordWebhookUrl, staffChatDiscordAvatarUrl,
                        msgOutgoing, msgIncoming, msgSpy, msgNotFound, msgNoReply, msgSelf,
                        soundEnabled, soundKey, soundVolume, soundPitch, apiPort,
                        publicChatEnabled, publicChatStream, publicChatReplayMessages,
                        publicChatMaxConnections, publicChatMaxConnectionsPerIp,
                        java.util.List.copyOf(publicChatAllowedOrigins),
                        ignoredServers, firstJoinFormat, discordWebhookUrl, discordJoinFormat,
                        discordLeaveFormat, discordSwapFormat, discordFirstJoinFormat,
                        dbUrl, dbUsername, dbPassword,
                        updateEnabled, updateInterval, updateIncludePre, updateRepo, updateToken,
                        vcEnabled, vcHomeTtl, streakResetHour,
                        LoginStreakService.minutesToSeconds(streakRequiredMinutes))
            } catch (e: IOException) {
                logger.error("Failed to load config, using defaults", e)
                return VelocityConfig("localhost", 6379, "", "crabutilities:staffchat",
                        "crabcraft:punishments", 10L, DEFAULT_FORMAT,
                        "", "https://mc-heads.net/head/{uuid}",
                        DEFAULT_MSG_OUTGOING, DEFAULT_MSG_INCOMING, DEFAULT_MSG_SPY,
                        DEFAULT_MSG_PLAYER_NOT_FOUND, DEFAULT_MSG_NO_REPLY_TARGET, DEFAULT_MSG_SELF,
                        true, "minecraft:entity.experience_orb.pickup", 1.0f, 1.0f, 8080,
                        false, "crabcraft:public-chat", 6, 64, 2,
                        listOf("https://crabcraft.net", "https://www.crabcraft.net"),
                        listOf(), "<yellow><name> joined the game for the first time</yellow>",
                        "", "{name} joined the game", "{name} left the game", "{name} swapped to the {server} server",
                        "{name} joined the game for the first time!",
                        "jdbc:postgresql://localhost:5432/crabcraft", "crabcraft", "",
                        true, 6L, false, "CrabCraftMC/CrabCraft", "",
                        true, 300L, LoginStreakService.DEFAULT_RESET_HOUR_UTC,
                        LoginStreakService.minutesToSeconds(LoginStreakService.DEFAULT_REQUIRED_PLAY_MINUTES))
            }
        }

    }
}
