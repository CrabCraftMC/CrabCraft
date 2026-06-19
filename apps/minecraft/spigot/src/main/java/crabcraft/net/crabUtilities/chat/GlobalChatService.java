package crabcraft.net.crabUtilities.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import crabcraft.net.crabUtilities.CrabUtilities;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
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
 * with a 2s timeout and a ping on start, a daemon {@code while(!interrupted)}
 * subscriber that reconnects with a ~3s backoff and swallows
 * {@code NoClassDefFoundError} on shutdown, and async publishes via
 * {@code Bukkit.getScheduler().runTaskAsynchronously}.
 *
 * <p>Each line is rendered with MiniMessage from the configured format,
 * with player text bound through {@link Placeholder} so it is never parsed
 * as MiniMessage. Delivery and ping sounds run on the main thread.
 */
public class GlobalChatService {

    private static final String CHANNEL = "crabutilities:globalchat";

    private final CrabUtilities plugin;
    private final String host;
    private final int port;
    private final String password;

    private final boolean enabled;
    private final String serverName;
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

    public GlobalChatService(CrabUtilities plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("redis.host", "localhost");
        this.port = plugin.getConfig().getInt("redis.port", 6379);
        this.password = plugin.getConfig().getString("redis.password", "");

        this.enabled = plugin.getConfig().getBoolean("global-chat.enabled", false);
        this.serverName = plugin.getConfig().getString("global-chat.server-name", "");
        this.format = plugin.getConfig().getString("global-chat.format",
                "<gray>[</gray><aqua><server></aqua><gray>]</gray> <display_name><gray>:</gray> <message>");

        boolean mentionsEnabled = plugin.getConfig().getBoolean("global-chat.mentions.enabled", true);
        String prefix = plugin.getConfig().getString("global-chat.mentions.prefix", "@");
        String highlight = plugin.getConfig().getString("global-chat.mentions.highlight",
                "<yellow><underlined><name></underlined></yellow>");
        this.mentionProcessor = new MentionProcessor(mentionsEnabled, prefix, highlight, miniMessage);

        this.soundEnabled = plugin.getConfig().getBoolean("global-chat.mentions.sound.enabled", true);
        String soundKey = plugin.getConfig().getString("global-chat.mentions.sound.key",
                "minecraft:block.note_block.pling");
        float volume = (float) plugin.getConfig().getDouble("global-chat.mentions.sound.volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("global-chat.mentions.sound.pitch", 1.0);
        this.mentionSound = Sound.sound(Key.key(soundKey), Sound.Source.MASTER, volume, pitch);
    }

    public void start() {
        // Disabled servers stay fully local: no publish, and no subscribe (so
        // they don't display the network's chat either). Mutes still apply —
        // that's the separately-started MuteCache, not this service.
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

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
        } catch (Exception e) {
            plugin.getLogger().severe("Global chat failed to connect to Redis: " + e.getMessage());
            jedisPool.close();
            jedisPool = null;
            return;
        }

        startSubscriber();
        plugin.getLogger().info("Global chat connected to Redis (enabled=" + enabled + ", serverId=" + serverId + ")");
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
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(pubSub, CHANNEL);
                } catch (NoClassDefFoundError e) {
                    break;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    plugin.getLogger().warning(
                            "Global chat subscriber disconnected, reconnecting in 3s: " + e.getMessage());
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
     * message component are bound via {@link Placeholder#component} and the
     * server name / username via {@link Placeholder#unparsed}, so nothing the
     * player typed is parsed as MiniMessage.
     */
    public RenderedLine renderLine(Component displayName, String username, String plainMessage, UUID senderUuid) {
        MentionProcessor.Result mention = mentionProcessor.process(plainMessage, senderUuid);
        Component line = miniMessage.deserialize(format,
                Placeholder.unparsed("server", serverName == null ? "" : serverName),
                Placeholder.component("display_name", displayName),
                Placeholder.unparsed("username", username),
                Placeholder.component("message", mention.message()));
        return new RenderedLine(line, mention.mentioned());
    }

    /**
     * Sends {@code line} to every online local player on the main thread,
     * playing the ping sound to anyone in {@code mentioned}.
     */
    public void deliverLocally(Component line, Set<UUID> mentioned) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(line);
                if (soundEnabled && mentioned.contains(p.getUniqueId())) {
                    p.playSound(mentionSound, Sound.Emitter.self());
                }
            }
        });
    }

    /**
     * Publishes a chat line to the network. The display name is serialized
     * with the gson Component serializer; {@code origin} is this process's
     * serverId so receivers can drop their own echoes.
     */
    public void publish(UUID senderUuid, String username, Component displayName, String plainMessage) {
        if (jedisPool == null) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("origin", serverId.toString());
        envelope.addProperty("server", serverName == null ? "" : serverName);
        envelope.addProperty("uuid", senderUuid.toString());
        envelope.addProperty("username", username);
        envelope.addProperty("displayName", GsonComponentSerializer.gson().serialize(displayName));
        envelope.addProperty("message", plainMessage);
        String payload = envelope.toString();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(CHANNEL, payload);
            } catch (Exception e) {
                plugin.getLogger().warning("Global chat publish failed: " + e.getMessage());
            }
        });
    }

    /** Parses an inbound envelope, drops our own echoes, then renders + delivers it. */
    private void handleIncoming(String payload) {
        JsonObject envelope = JsonParser.parseString(payload).getAsJsonObject();
        String origin = envelope.has("origin") ? envelope.get("origin").getAsString() : "";
        if (serverId.toString().equals(origin)) {
            return;
        }
        String username = envelope.has("username") ? envelope.get("username").getAsString() : "";
        String message = envelope.has("message") ? envelope.get("message").getAsString() : "";
        String uuidStr = envelope.has("uuid") ? envelope.get("uuid").getAsString() : null;
        UUID senderUuid;
        try {
            senderUuid = uuidStr == null ? new UUID(0L, 0L) : UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            senderUuid = new UUID(0L, 0L);
        }

        Component displayName;
        String displayJson = envelope.has("displayName") ? envelope.get("displayName").getAsString() : null;
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
    }

    public void shutdown() {
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
