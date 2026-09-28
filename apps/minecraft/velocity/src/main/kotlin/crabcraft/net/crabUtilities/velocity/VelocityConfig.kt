package crabcraft.net.crabUtilities.velocity

import crabcraft.net.crabUtilities.velocity.db.LoginStreakService
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.slf4j.Logger
import org.spongepowered.configurate.BasicConfigurationNode
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.NodeStyle
import org.spongepowered.configurate.yaml.YamlConfigurationLoader

open class VelocityConfig private constructor(root: ConfigurationNode, fallbackDefaults: Boolean) {
    private val redisHost: String
    private val redisPort: Int
    private val redisPassword: String
    private val redisChannel: String
    private val redisPunishmentStream: String
    private val redisPunishmentWatchIntervalSeconds: Long
    private val staffChatFormat: String
    private val staffChatDiscordWebhookUrl: String
    private val staffChatDiscordAvatarUrl: String
    private val msgOutgoingFormat: String
    private val msgIncomingFormat: String
    private val msgSpyFormat: String
    private val msgPlayerNotFound: String
    private val msgNoReplyTarget: String
    private val msgSelfError: String
    private val msgIncomingSoundEnabled: Boolean
    private val msgIncomingSoundKey: String
    private val msgIncomingSoundVolume: Float
    private val msgIncomingSoundPitch: Float
    private val apiPort: Int
    private val publicChatEnabled: Boolean
    private val publicChatStream: String
    private val publicChatReplayMessages: Int
    private val publicChatMaxConnections: Int
    private val publicChatMaxConnectionsPerIp: Int
    private val publicChatAllowedOrigins: List<String>
    private val ignoredServers: List<String>
    private val silentJoinHosts: List<String>
    private val firstJoinFormat: String
    private val discordWebhookUrl: String
    private val discordJoinFormat: String
    private val discordLeaveFormat: String
    private val discordSwapFormat: String
    private val discordFirstJoinFormat: String
    private val dbUrl: String
    private val dbUsername: String
    private val dbPassword: String
    private val updateEnabled: Boolean
    private val updateCheckIntervalHours: Long
    private val updateIncludePrereleases: Boolean
    private val updateGithubRepo: String
    private val updateGithubToken: String
    private val voicechatCrossServerEnabled: Boolean
    private val voicechatPlayerHomeTtlSeconds: Long
    private val loginStreakResetHourUtc: Int
    private val loginStreakRequiredPlaySeconds: Int

    init {
        val redis = root.node("redis")
        redisHost = redis.node("host").getString("localhost")!!
        redisPort = redis.node("port").getInt(6379)
        redisPassword = redis.node("password").getString("")!!
        redisChannel = redis.node("channel").getString("crabutilities:staffchat")!!
        redisPunishmentStream = redis.node("punishment-stream").getString("crabcraft:punishments")!!
        redisPunishmentWatchIntervalSeconds = maxOf(1L, redis.node("punishment-watch-interval-seconds").getLong(10L))

        staffChatFormat = root.node("staff-chat", "format").getString(DEFAULT_FORMAT)!!
        val staffDiscord = root.node("staff-chat", "discord")
        staffChatDiscordWebhookUrl = staffDiscord.node("webhook-url").getString("")!!
        staffChatDiscordAvatarUrl = staffDiscord.node("avatar-url").getString("https://mc-heads.net/head/{uuid}")!!

        val messages = root.node("private-messages")
        msgOutgoingFormat = messages.node("outgoing-format").getString(DEFAULT_MSG_OUTGOING)!!
        msgIncomingFormat = messages.node("incoming-format").getString(DEFAULT_MSG_INCOMING)!!
        msgSpyFormat = messages.node("spy-format").getString(DEFAULT_MSG_SPY)!!
        msgPlayerNotFound = messages.node("player-not-found").getString(DEFAULT_MSG_PLAYER_NOT_FOUND)!!
        msgNoReplyTarget = messages.node("no-reply-target").getString(DEFAULT_MSG_NO_REPLY_TARGET)!!
        msgSelfError = messages.node("self-error").getString(DEFAULT_MSG_SELF)!!
        val sound = messages.node("incoming-sound")
        msgIncomingSoundEnabled = sound.node("enabled").getBoolean(true)
        msgIncomingSoundKey = sound.node("sound").getString("minecraft:entity.experience_orb.pickup")!!
        msgIncomingSoundVolume = sound.node("volume").getDouble(1.0).toFloat()
        msgIncomingSoundPitch = sound.node("pitch").getDouble(1.0).toFloat()
        apiPort = root.node("api", "port").getInt(8080)

        val publicChat = root.node("public-chat")
        publicChatEnabled = publicChat.node("enabled").getBoolean(false)
        publicChatStream =
            publicChat.node("stream").getString("crabcraft:public-chat")?.takeUnless { it.isBlank() }
                ?: "crabcraft:public-chat"
        publicChatReplayMessages = publicChat.node("replay-messages").getInt(6).coerceIn(1, 20)
        publicChatMaxConnections = maxOf(1, publicChat.node("max-connections").getInt(64))
        publicChatMaxConnectionsPerIp = maxOf(1, publicChat.node("max-connections-per-ip").getInt(2))
        val originsNode = publicChat.node("allowed-origins")
        val origins =
            if (originsNode.virtual()) emptyList()
            else originsNode.childrenList().mapNotNull { it.string?.takeUnless(String::isBlank) }
        publicChatAllowedOrigins =
            if (fallbackDefaults) java.util.List.of("https://crabcraft.net", "https://www.crabcraft.net")
            else java.util.List.copyOf(origins)

        val joinLeave = root.node("join-leave-messages")
        val ignored: MutableList<String> = if (fallbackDefaults) java.util.List.of() else arrayListOf()
        val ignoredNode = joinLeave.node("ignored-servers")
        if (!ignoredNode.virtual()) {
            for (child in ignoredNode.childrenList()) {
                child.string?.let { ignored.add(it.lowercase(Locale.getDefault())) }
            }
        }
        ignoredServers = ignored
        val silentHosts = arrayListOf<String>()
        val silentNode = joinLeave.node("silent-join-hosts")
        if (!silentNode.virtual()) {
            for (child in silentNode.childrenList()) {
                child.string?.takeUnless(String::isBlank)?.let { silentHosts.add(it.lowercase(Locale.getDefault())) }
            }
        }
        silentJoinHosts = if (fallbackDefaults) java.util.List.of("mods.crabcraft.net") else silentHosts
        firstJoinFormat =
            joinLeave.node("first-join").getString("<yellow><name> joined the game for the first time</yellow>")!!
        val discord = joinLeave.node("discord")
        discordWebhookUrl = discord.node("webhook-url").getString("")!!
        discordJoinFormat = discord.node("join").getString("{name} joined the game")!!
        discordLeaveFormat = discord.node("leave").getString("{name} left the game")!!
        discordSwapFormat = discord.node("swap").getString("{name} swapped to the {server} server")!!
        discordFirstJoinFormat = discord.node("first-join").getString("{name} joined the game for the first time!")!!

        val database = root.node("database")
        dbUrl = database.node("url").getString("jdbc:postgresql://localhost:5432/crabcraft")!!
        dbUsername = database.node("username").getString("crabcraft")!!
        dbPassword = database.node("password").getString("")!!
        val update = root.node("auto-update")
        updateEnabled = update.node("enabled").getBoolean(true)
        updateCheckIntervalHours = update.node("check-interval-hours").getLong(6L)
        updateIncludePrereleases = update.node("include-prereleases").getBoolean(false)
        updateGithubRepo = update.node("github-repo").getString("CrabCraftMC/CrabCraft")!!
        updateGithubToken = update.node("github-token").getString("")!!
        val voicechat = root.node("voicechat", "cross-server")
        voicechatCrossServerEnabled = voicechat.node("enabled").getBoolean(true)
        voicechatPlayerHomeTtlSeconds = voicechat.node("player-home-ttl-seconds").getLong(300L)
        loginStreakResetHourUtc =
            root.node("login-streaks", "reset-hour-utc").getInt(LoginStreakService.DEFAULT_RESET_HOUR_UTC)
        val requiredMinutes =
            root.node("login-streaks", "required-play-minutes").getInt(LoginStreakService.DEFAULT_REQUIRED_PLAY_MINUTES)
        loginStreakRequiredPlaySeconds = LoginStreakService.minutesToSeconds(requiredMinutes)
    }

    open fun getRedisHost(): String = redisHost

    open fun getRedisPort(): Int = redisPort

    open fun getRedisPassword(): String = redisPassword

    open fun getRedisChannel(): String = redisChannel

    open fun getRedisPunishmentStream(): String = redisPunishmentStream

    open fun getRedisPunishmentWatchIntervalSeconds(): Long = redisPunishmentWatchIntervalSeconds

    open fun getStaffChatFormat(): String = staffChatFormat

    open fun getStaffChatDiscordWebhookUrl(): String = staffChatDiscordWebhookUrl

    open fun getStaffChatDiscordAvatarUrl(): String = staffChatDiscordAvatarUrl

    open fun getMsgOutgoingFormat(): String = msgOutgoingFormat

    open fun getMsgIncomingFormat(): String = msgIncomingFormat

    open fun getMsgSpyFormat(): String = msgSpyFormat

    open fun getMsgPlayerNotFound(): String = msgPlayerNotFound

    open fun getMsgNoReplyTarget(): String = msgNoReplyTarget

    open fun getMsgSelfError(): String = msgSelfError

    open fun isMsgIncomingSoundEnabled(): Boolean = msgIncomingSoundEnabled

    open fun getMsgIncomingSoundKey(): String = msgIncomingSoundKey

    open fun getMsgIncomingSoundVolume(): Float = msgIncomingSoundVolume

    open fun getMsgIncomingSoundPitch(): Float = msgIncomingSoundPitch

    open fun getApiPort(): Int = apiPort

    open fun isPublicChatEnabled(): Boolean = publicChatEnabled

    open fun getPublicChatStream(): String = publicChatStream

    open fun getPublicChatReplayMessages(): Int = publicChatReplayMessages

    open fun getPublicChatMaxConnections(): Int = publicChatMaxConnections

    open fun getPublicChatMaxConnectionsPerIp(): Int = publicChatMaxConnectionsPerIp

    open fun getPublicChatAllowedOrigins(): List<String> = publicChatAllowedOrigins

    open fun getIgnoredServers(): List<String> = ignoredServers

    open fun getSilentJoinHosts(): List<String> = silentJoinHosts

    open fun getFirstJoinFormat(): String = firstJoinFormat

    open fun getDiscordWebhookUrl(): String = discordWebhookUrl

    open fun getDiscordJoinFormat(): String = discordJoinFormat

    open fun getDiscordLeaveFormat(): String = discordLeaveFormat

    open fun getDiscordSwapFormat(): String = discordSwapFormat

    open fun getDiscordFirstJoinFormat(): String = discordFirstJoinFormat

    open fun getDbUrl(): String = dbUrl

    open fun getDbUsername(): String = dbUsername

    open fun getDbPassword(): String = dbPassword

    open fun isUpdateEnabled(): Boolean = updateEnabled

    open fun getUpdateCheckIntervalHours(): Long = updateCheckIntervalHours

    open fun isUpdateIncludePrereleases(): Boolean = updateIncludePrereleases

    open fun getUpdateGithubRepo(): String = updateGithubRepo

    open fun getUpdateGithubToken(): String = updateGithubToken

    open fun isVoicechatCrossServerEnabled(): Boolean = voicechatCrossServerEnabled

    open fun getVoicechatPlayerHomeTtlSeconds(): Long = voicechatPlayerHomeTtlSeconds

    open fun getLoginStreakResetHourUtc(): Int = loginStreakResetHourUtc

    open fun getLoginStreakRequiredPlaySeconds(): Int = loginStreakRequiredPlaySeconds

    companion object {
        private const val DEFAULT_FORMAT =
            "<dark_gray>[<aqua>SC</aqua>]</dark_gray> <gray><sender></gray> <dark_gray>></dark_gray> <white><message></white>"
        private const val DEFAULT_MSG_OUTGOING = "<gold>(to <target>) <white><message>"
        private const val DEFAULT_MSG_INCOMING = "<gold>(from <sender>) <white><message>"
        private const val DEFAULT_MSG_SPY = "<gray>[SPY] (<sender> → <target>) <white><message>"
        private const val DEFAULT_MSG_PLAYER_NOT_FOUND = "<red>Player not found or not online."
        private const val DEFAULT_MSG_NO_REPLY_TARGET = "<red>You have no one to reply to."
        private const val DEFAULT_MSG_SELF = "<red>You can't message yourself."

        @JvmStatic
        fun load(dataDirectory: Path, logger: Logger): VelocityConfig {
            try {
                Files.createDirectories(dataDirectory)
                val configPath = dataDirectory.resolve("config.yml")
                if (!Files.exists(configPath)) {
                    VelocityConfig::class.java.getResourceAsStream("/config.yml").use { input ->
                        if (input != null) Files.copy(input, configPath)
                    }
                }
                val loader = YamlConfigurationLoader.builder().path(configPath).nodeStyle(NodeStyle.BLOCK).build()
                val root = loader.load()
                // Merge missing keys from bundled defaults into the user's config.
                VelocityConfig::class.java.getResourceAsStream("/config.yml").use { defaultsInput ->
                    if (defaultsInput != null) {
                        val defaults =
                            YamlConfigurationLoader.builder()
                                .source { BufferedReader(InputStreamReader(defaultsInput)) }
                                .build()
                                .load()
                        root.mergeFrom(defaults)
                        // mergeFrom adds the replacement reset-hour-utc key but does not prune its predecessor.
                        root.node("login-streaks").removeChild("buffer-hours")
                        root.removeChild("restricted-area")
                        loader.save(root)
                    }
                }
                return VelocityConfig(root, false)
            } catch (error: IOException) {
                logger.error("Failed to load config, using defaults", error)
                return VelocityConfig(BasicConfigurationNode.root(), true)
            }
        }
    }
}
