package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class NicknameListener {

    private static final String CHANNEL = "crabutilities:nicknames";

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

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
            UUID uuid = UUID.fromString(in.readUTF());
            String nickname = in.readUTF();
            plugin.getNicknameCache().setNickname(uuid, nickname);
            plugin.getPendingJoinManager().complete(uuid);

            // Persist nickname to PostgreSQL
            final String uuidStr = uuid.toString();
            final String plain = plugin.getNicknameCache().getPlainNickname(uuid);
            final String raw = plugin.getNicknameCache().getRawNickname(uuid);
            CompletableFuture.runAsync(() -> {
                plugin.getPgWriter().updateNickname(uuidStr, plain, raw);
            });
        } catch (IOException | IllegalArgumentException e) {
            plugin.getLogger().warn("Failed to parse nickname plugin message", e);
        }
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
}
