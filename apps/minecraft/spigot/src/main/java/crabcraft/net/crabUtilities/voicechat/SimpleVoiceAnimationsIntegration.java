package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabUtilities;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spigot implementation of the Simple Voice Animations compatibility
 * version 3 play protocol.
 */
public final class SimpleVoiceAnimationsIntegration implements Listener, PluginMessageListener {

    static final String UPDATE_PREFERENCES_CHANNEL =
            "simplevoiceanimations:update_preferences";
    static final String PLAYER_PREFERENCES_CHANNEL =
            "simplevoiceanimations:player_preferences";

    private static final int HEAD_ANIMATION_STYLE_COUNT = 6;
    private static final float MIN_SPLIT_HEIGHT = 0.5F;
    private static final float MAX_SPLIT_HEIGHT = 7.5F;
    private static final float DEFAULT_SPLIT_HEIGHT = 0.5F;
    private static final float MIN_INTENSITY = 0F;
    private static final float MAX_INTENSITY = 2F;
    private static final float DEFAULT_INTENSITY = 1F;
    private static final Preferences DEFAULT_PREFERENCES =
            new Preferences(0, 2F, DEFAULT_INTENSITY);

    private final CrabUtilities plugin;
    private final Map<UUID, Preferences> preferences = new ConcurrentHashMap<>();
    private final Set<UUID> synchronisedClients = ConcurrentHashMap.newKeySet();
    private boolean active;

    public SimpleVoiceAnimationsIntegration(@NotNull CrabUtilities plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (active) {
            return;
        }

        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(
                plugin,
                UPDATE_PREFERENCES_CHANNEL,
                this);
        messenger.registerOutgoingPluginChannel(
                plugin,
                PLAYER_PREFERENCES_CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        active = true;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            initialisePlayer(player);
        }
    }

    public void shutdown() {
        active = false;
        HandlerList.unregisterAll(this);

        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(
                plugin,
                UPDATE_PREFERENCES_CHANNEL,
                this);
        messenger.unregisterOutgoingPluginChannel(
                plugin,
                PLAYER_PREFERENCES_CHANNEL);

        synchronisedClients.clear();
        preferences.clear();
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public void onPluginMessageReceived(
            @NotNull String channel,
            @NotNull Player player,
            byte @NotNull [] message) {
        if (!active || !UPDATE_PREFERENCES_CHANNEL.equals(channel)) {
            return;
        }

        Preferences updated;
        try {
            updated = decodePreferences(message);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().fine(
                    "Ignored malformed Simple Voice Animations preferences from "
                            + player.getName());
            return;
        }

        UUID playerId = player.getUniqueId();
        preferences.put(playerId, updated);
        synchroniseClient(player);
        broadcastPreferences(playerId, updated);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        initialisePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRegisterChannel(@NotNull PlayerRegisterChannelEvent event) {
        if (!PLAYER_PREFERENCES_CHANNEL.equals(event.getChannel())) {
            return;
        }

        Player player = event.getPlayer();
        preferences.putIfAbsent(player.getUniqueId(), DEFAULT_PREFERENCES);
        synchroniseClient(player);
    }

    @EventHandler
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        synchronisedClients.remove(playerId);
        preferences.remove(playerId);
    }

    private void initialisePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        Preferences initial = preferences.putIfAbsent(playerId, DEFAULT_PREFERENCES);
        if (player.getListeningPluginChannels().contains(PLAYER_PREFERENCES_CHANNEL)) {
            synchroniseClient(player);
        }
        broadcastPreferences(
                playerId,
                initial == null ? DEFAULT_PREFERENCES : initial);
    }

    private void synchroniseClient(Player player) {
        if (!synchronisedClients.add(player.getUniqueId())) {
            return;
        }

        preferences.forEach((playerId, playerPreferences) ->
                sendPreferences(player, playerId, playerPreferences));
    }

    private void broadcastPreferences(UUID playerId, Preferences playerPreferences) {
        for (Player recipient : plugin.getServer().getOnlinePlayers()) {
            if (synchronisedClients.contains(recipient.getUniqueId())) {
                sendPreferences(recipient, playerId, playerPreferences);
            }
        }
    }

    private void sendPreferences(
            Player recipient,
            UUID playerId,
            Preferences playerPreferences) {
        recipient.sendPluginMessage(
                plugin,
                PLAYER_PREFERENCES_CHANNEL,
                encodePlayerPreferences(playerId, playerPreferences));
    }

    static Preferences decodePreferences(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try {
            int headAnimationStyle = readVarInt(buffer);
            if (headAnimationStyle < 0 || headAnimationStyle >= HEAD_ANIMATION_STYLE_COUNT) {
                throw new IllegalArgumentException("Unknown head animation style");
            }
            if (buffer.remaining() != Float.BYTES * 2) {
                throw new IllegalArgumentException("Unexpected preferences payload length");
            }

            float splitHeight = clamp(
                    buffer.getFloat(),
                    MIN_SPLIT_HEIGHT,
                    MAX_SPLIT_HEIGHT,
                    DEFAULT_SPLIT_HEIGHT);
            float intensity = clamp(
                    buffer.getFloat(),
                    MIN_INTENSITY,
                    MAX_INTENSITY,
                    DEFAULT_INTENSITY);
            return new Preferences(headAnimationStyle, splitHeight, intensity);
        } catch (BufferUnderflowException exception) {
            throw new IllegalArgumentException("Truncated preferences payload", exception);
        }
    }

    static byte[] encodePlayerPreferences(UUID playerId, Preferences playerPreferences) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2 + 1 + Float.BYTES * 2);
        buffer.putLong(playerId.getMostSignificantBits());
        buffer.putLong(playerId.getLeastSignificantBits());
        buffer.put((byte) playerPreferences.headAnimationStyle());
        buffer.putFloat(playerPreferences.splitHeight());
        buffer.putFloat(playerPreferences.intensity());
        return buffer.array();
    }

    private static int readVarInt(ByteBuffer buffer) {
        int value = 0;
        for (int byteIndex = 0; byteIndex < 5; byteIndex++) {
            byte current = buffer.get();
            value |= (current & 0x7F) << (byteIndex * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("VarInt is too large");
    }

    private static float clamp(float value, float minimum, float maximum, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Preferences(int headAnimationStyle, float splitHeight, float intensity) {
    }
}
