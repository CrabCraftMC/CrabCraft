package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public class NicknameListener {

    private static final String CHANNEL = "crabutilities:nicknames";
    private static final MinecraftChannelIdentifier NICKNAME_CHANNEL =
            MinecraftChannelIdentifier.from(CHANNEL);

    // Origin of a Spigot -> proxy nickname message.
    //   CHANGE — player ran /nick: authoritative set or clear.
    //   JOIN   — a backend reporting its current local nick on join. Untrusted:
    //            may be stale or empty if EssentialsX hasn't loaded the user.
    private static final String ORIGIN_JOIN = "JOIN";
    private static final String ORIGIN_CHANGE = "CHANGE";

    private final CrabUtilitiesVelocity plugin;

    public NicknameListener(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().getId().equals(CHANNEL)) return;
        // Only accept messages from backend servers
        if (!(event.getSource() instanceof ServerConnection)) return;

        // Don't forward to the client
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        final UUID uuid;
        final String nickname;
        final String origin;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
            uuid = UUID.fromString(in.readUTF());
            nickname = in.readUTF();
            origin = readOrigin(in);
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warn("Failed to parse nickname plugin message", e);
            return;
        }

        if (ORIGIN_JOIN.equals(origin)) {
            handleJoinReport(uuid, nickname);
        } else {
            // Explicit /nick change — authoritative set or clear.
            plugin.getNicknameCache().setNickname(uuid, nickname);
            plugin.getPendingJoinManager().complete(uuid);
            persist(uuid);
        }
    }

    /**
     * Handles a backend's join-time report of its local nickname. This must
     * never let a backend overwrite the authoritative nickname, otherwise a
     * server whose EssentialsX data has lost (or never had) the nick reverts
     * it for everyone.
     */
    private void handleJoinReport(UUID uuid, String reported) {
        String cached = plugin.getNicknameCache().getRawNickname(uuid);
        if (cached != null) {
            // Proxy already holds the authoritative nick. If the backend
            // disagrees (stale or missing local data), correct the backend
            // instead of overwriting the cache/DB.
            if (!cached.equals(reported)) {
                pushToBackend(uuid, cached);
            }
            plugin.getPendingJoinManager().complete(uuid);
            return;
        }

        // No cached value yet — resolve against the database, which is the
        // source of truth. The DB seed in ConnectionListener#onLogin usually
        // populates the cache first; this covers the case where it hasn't
        // finished, and migrates legacy nicks set directly on a backend.
        plugin.runDatabaseTask("nickname-join-resolve", () -> {
            String dbRaw = plugin.getPgWriter().loadRawNickname(uuid.toString());
            if (dbRaw != null && !dbRaw.isEmpty()) {
                plugin.getNicknameCache().setNickname(uuid, dbRaw);
                if (!dbRaw.equals(reported)) {
                    pushToBackend(uuid, dbRaw);
                }
            } else if (reported != null && !reported.isEmpty()) {
                // DB has no nickname but the backend does — adopt and persist
                // it (e.g. a nick set on a backend before proxy sync existed).
                plugin.getNicknameCache().setNickname(uuid, reported);
                persist(uuid);
            }
            // else: both empty — genuinely no nickname, nothing to do.
            plugin.getPendingJoinManager().complete(uuid);
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getPendingJoinManager().remove(uuid);
        plugin.getNicknameCache().remove(uuid);
        if (plugin.getMessageManager() != null) {
            plugin.getMessageManager().clearReplyTargets(uuid);
            plugin.getMessageManager().clearSpy(uuid);
        }
    }

    /** Persist the currently-cached nickname for {@code uuid} to PostgreSQL. */
    private void persist(UUID uuid) {
        final String uuidStr = uuid.toString();
        final String plain = plugin.getNicknameCache().getPlainNickname(uuid);
        final String raw = plugin.getNicknameCache().getRawNickname(uuid);
        plugin.runDatabaseTask("nickname-persist",
                () -> plugin.getPgWriter().updateNickname(uuidStr, plain, raw));
    }

    /** Push the authoritative nickname down to the player's current backend. */
    private void pushToBackend(UUID uuid, String raw) {
        plugin.getServer().getPlayer(uuid).ifPresent(player ->
                player.getCurrentServer().ifPresent(conn -> {
                    try {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        DataOutputStream out = new DataOutputStream(bytes);
                        out.writeUTF(uuid.toString());
                        out.writeUTF(raw);
                        conn.sendPluginMessage(NICKNAME_CHANNEL, bytes.toByteArray());
                    } catch (IOException e) {
                        plugin.getLogger().warn("Failed to push nickname to backend for {}", uuid, e);
                    }
                }));
    }

    /**
     * Reads the trailing origin flag. Falls back to {@code CHANGE} for older
     * backends that don't send one, preserving the previous behavior.
     */
    private static String readOrigin(DataInputStream in) {
        try {
            return in.readUTF();
        } catch (IOException e) {
            return ORIGIN_CHANGE;
        }
    }
}
