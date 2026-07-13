package crabcraft.net.crabUtilities.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.NicknameComponentResolver;
import crabcraft.net.crabUtilities.settings.PlayerSettingsService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.Set;
import java.util.UUID;

/**
 * Formats this backend's chat and bridges it to other opted-in backends
 * over Redis pub/sub on {@code crabutilities:globalchat}.
 *
 * <p>Pool/subscriber idiom follows {@code RedisVoiceBus}: a JedisPool
 * with a 2s timeout, a daemon {@code while(!interrupted)}
 * subscriber that reconnects with a ~3s backoff and swallows
 * {@code NoClassDefFoundError} on shutdown, and async publishes via
 * {@code Bukkit.getScheduler().runTaskAsynchronously}.
 *
 * <p>Each line is rendered with MiniMessage from the configured format. Player
 * text is first parsed by {@link SafeChatMiniMessage}'s visual-only whitelist,
 * then bound through {@link Placeholder#component}. Delivery and ping sounds
 * run on the main thread.
 */
public class GlobalChatService {

    private static final String CHANNEL = "crabutilities:globalchat";
    private final CrabUtilities plugin;
    private final String host;
    private final int port;
    private final String password;

    private final boolean enabled;
    private final String format;
    private final MentionProcessor mentionProcessor;
    private final boolean soundEnabled;
    private final Sound mentionSound;

    /** Identity of this process; used to drop our own echoed messages. */
    private final UUID serverId = UUID.randomUUID();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private JedisPool jedisPool;
    private JedisPubSub pubSub;
    private Thread subscriberThread;
    private volatile boolean stopped;

    public GlobalChatService(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");

        this.enabled = plugin.getConfig().getBoolean("global-chat.enabled", false);
        this.format = plugin.getConfig().getString("global-chat.format",
                "<display_name><gray>:</gray> <message>");

        boolean mentionsEnabled = plugin.getConfig().getBoolean("global-chat.mentions.enabled", true);
        String prefix = plugin.getConfig().getString("global-chat.mentions.prefix", "@");
        String highlight = plugin.getConfig().getString("global-chat.mentions.highlight",
                "<yellow><name></yellow>");
        this.mentionProcessor = new MentionProcessor(mentionsEnabled, prefix, highlight,
                miniMessage, plugin.getEssentials());

        this.soundEnabled = plugin.getConfig().getBoolean("global-chat.mentions.sound.enabled", true);
        String soundKey = plugin.getConfig().getString("global-chat.mentions.sound.key",
                "minecraft:block.note_block.pling");
        float volume = (float) plugin.getConfig().getDouble("global-chat.mentions.sound.volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("global-chat.mentions.sound.pitch", 1.0);
        this.mentionSound = Sound.sound(Key.key(soundKey), Sound.Source.MASTER, volume, pitch);
    }

    public void start() {
        // Disabled servers stay fully local: no publish, and no subscribe (so
        // they don't display the network's chat either).
        if (!enabled) {
            plugin.getLogger().info("Global chat disabled for this server; chat stays local.");
            return;
        }

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        if (password != null && !password.isEmpty()) {
            jedisPool = new JedisPool(poolConfig, host, port, 2000, password);
        } else {
            jedisPool = new JedisPool(poolConfig, host, port, 2000);
        }

        startSubscriber();
        plugin.getLogger().info("Global chat started (enabled=" + enabled + ", serverId=" + serverId
                + "); Redis will be retried asynchronously if unavailable.");
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void startSubscriber() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    handleIncoming(message);
                } catch (Throwable t) {
                    plugin.getLogger().fine("Global chat handler threw: " + t.getMessage());
                }
            }
        };
        subscriberThread = new Thread(() -> {
            boolean warned = false;
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                JedisPool pool = jedisPool;
                if (pool == null || pool.isClosed()) break;
                try (Jedis jedis = pool.getResource()) {
                    if (warned) {
                        plugin.getLogger().info("Global chat Redis subscriber reconnected.");
                        warned = false;
                    }
                    jedis.subscribe(pubSub, CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (!warned) {
                        plugin.getLogger().warning(
                                "Global chat Redis subscriber unavailable; reconnecting in 3s: " + e.getMessage());
                        warned = true;
                    } else {
                        plugin.getLogger().fine("Global chat subscriber disconnected: " + e.getMessage());
                    }
                    try { Thread.sleep(3000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "CrabUtilities-GlobalChat");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    /** Holds a rendered chat line and the local players it pinged. */
    public record RenderedLine(Component line, Set<UUID> mentioned) {}

    /**
     * Renders one chat line from the configured format. The display name and
     * safely parsed message are bound via {@link Placeholder#component}; the
     * username uses {@link Placeholder#unparsed}.
     *
     * <p>Must run on the main thread because mention resolution walks the
     * online Bukkit player list and reads display names.
     */
    public RenderedLine renderLine(Component displayName, String username, String rawMessage, UUID senderUuid) {
        Component styledMessage = SafeChatMiniMessage.deserialize(rawMessage);
        MentionProcessor.Result mention = mentionProcessor.process(styledMessage, senderUuid);
        Component clickableDisplayName = displayName.clickEvent(ClickEvent.suggestCommand(messageCommand(username)));
        Component line = miniMessage.deserialize(format,
                Placeholder.component("display_name", clickableDisplayName),
                Placeholder.unparsed("username", username),
                Placeholder.component("message", mention.message()));
        return new RenderedLine(line, mention.mentioned());
    }

    private static String messageCommand(String username) {
        return "/msg " + username + " ";
    }

    public void handleLocalChat(UUID senderUuid, String rawMessage) {
        runOnMain(() -> {
            if (stopped) {
                return;
            }
            Player player = Bukkit.getPlayer(senderUuid);
            if (player == null || !player.isOnline()) {
                return;
            }

            String username = player.getName();
            // Resolve the nick from EssentialsX directly: player.displayName() does
            // not reliably carry the nick's colours (especially hex) on this server.
            // Fall back to the vanilla display name when no nick is set.
            Component displayName = NicknameComponentResolver.forPlayer(plugin.getEssentials(), player);
            if (displayName == null) {
                displayName = player.displayName();
            }
            RenderedLine rendered = renderLine(displayName, username, rawMessage, senderUuid);

            deliverLocally(rendered.line(), rendered.mentioned());
            publish(senderUuid, username, displayName, rawMessage);
        });
    }

    /**
     * Sends {@code line} to every online local player on the main thread,
     * playing the ping sound to anyone in {@code mentioned}.
     */
    public void deliverLocally(Component line, Set<UUID> mentioned) {
        runOnMain(() -> {
            if (stopped) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(line);
                if (soundEnabled && mentioned.contains(player.getUniqueId())
                        && mentionPingsEnabled(player.getUniqueId())) {
                    player.playSound(mentionSound, Sound.Emitter.self());
                }
            }
        });
    }

    /**
     * Publishes a chat line to the network. The display name is serialized
     * with the gson Component serializer; {@code origin} is this process's
     * serverId so receivers can drop their own echoes.
     */
    public void publish(UUID senderUuid, String username, Component displayName, String rawMessage) {
        if (stopped) return;
        JedisPool pool = jedisPool;
        if (pool == null || pool.isClosed()) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("origin", serverId.toString());
        envelope.addProperty("uuid", senderUuid.toString());
        envelope.addProperty("username", username);
        envelope.addProperty("displayName", GsonComponentSerializer.gson().serialize(displayName));
        envelope.addProperty("message", rawMessage);
        String payload = envelope.toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(CHANNEL, payload);
            } catch (Exception e) {
                plugin.getLogger().warning("Global chat publish failed: " + e.getMessage());
            }
        });
    }

    /** Parses an inbound envelope, drops our own echoes, then renders + delivers it. */
    private void handleIncoming(String payload) {
        if (stopped) return;
        JsonObject envelope = JsonParser.parseString(payload).getAsJsonObject();
        String origin = envelope.has("origin") ? envelope.get("origin").getAsString() : "";
        if (serverId.toString().equals(origin)) {
            return;
        }
        String username = envelope.has("username") ? envelope.get("username").getAsString() : "";
        String message = envelope.has("message") ? envelope.get("message").getAsString() : "";
        String uuidStr = envelope.has("uuid") ? envelope.get("uuid").getAsString() : null;
        UUID parsedSenderUuid;
        try {
            parsedSenderUuid = uuidStr == null ? new UUID(0L, 0L) : UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            parsedSenderUuid = new UUID(0L, 0L);
        }
        UUID senderUuid = parsedSenderUuid;

        String displayJson = envelope.has("displayName") ? envelope.get("displayName").getAsString() : null;
        runOnMain(() -> {
            if (stopped) {
                return;
            }
            Component displayName;
            if (displayJson != null && !displayJson.isEmpty()) {
                try {
                    displayName = GsonComponentSerializer.gson().deserialize(displayJson);
                } catch (Exception e) {
                    displayName = Component.text(username);
                }
            } else {
                displayName = Component.text(username);
            }

            RenderedLine rendered = renderLine(displayName, username, message, senderUuid);
            deliverLocally(rendered.line(), rendered.mentioned());
        });
    }

    /**
     * Whether this player should hear the mention ping. Defaults to true if the
     * settings service isn't up yet, so pings work even before settings load.
     */
    private boolean mentionPingsEnabled(java.util.UUID uuid) {
        PlayerSettingsService settings = plugin.getPlayerSettingsService();
        return settings == null || settings.isMentionPingsEnabled(uuid);
    }

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void shutdown() {
        stopped = true;
        if (pubSub != null) {
            try { pubSub.unsubscribe(); } catch (Exception ignored) {}
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            try { jedisPool.close(); } catch (NoClassDefFoundError ignored) {}
            jedisPool = null;
        }
    }
}
