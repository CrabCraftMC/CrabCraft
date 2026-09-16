package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fail-closed mirror of the authoritative EssentialsX state on each backend.
 * A player is public only after their current server has explicitly reported
 * that they are visible.
 */
public final class VanishManager {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(VanishBridgeProtocol.CHANNEL);

    private final CrabUtilitiesVelocity plugin;
    private final ConcurrentHashMap<UUID, Snapshot> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PendingSession> pendingSessions = new ConcurrentHashMap<>();

    public VanishManager(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getChannelRegistrar().register(CHANNEL);
        plugin.getServer().getEventManager().register(plugin, this);
    }

    public void shutdown() {
        plugin.getServer().getEventManager().unregisterListener(plugin, this);
        plugin.getServer().getChannelRegistrar().unregister(CHANNEL);
        states.clear();
        pendingSessions.clear();
    }

    /**
     * Starts a backend session and invokes {@code completion} once that exact
     * backend has supplied the player's visibility state.
     */
    public void beginSession(Player player, RegisteredServer server, Consumer<Boolean> completion) {
        String serverName = server.getServerInfo().getName();
        PendingSession pending = new PendingSession(serverName, completion);
        pendingSessions.put(player.getUniqueId(), pending);

        Snapshot current = states.get(player.getUniqueId());
        if (current != null && current.serverName.equals(serverName)
                && pendingSessions.remove(player.getUniqueId(), pending)) {
            completion.accept(!current.vanished);
        }
    }

    public boolean isVisible(Player player) {
        ServerConnection connection = player.getCurrentServer().orElse(null);
        if (connection == null) return false;
        Snapshot state = states.get(player.getUniqueId());
        return state != null && isPubliclyVisible(
                state.serverName,
                state.vanished,
                connection.getServer().getServerInfo().getName());
    }

    static boolean isPubliclyVisible(String reportedServer, boolean vanished, String currentServer) {
        return reportedServer != null
                && currentServer != null
                && !vanished
                && reportedServer.equals(currentServer);
    }

    public List<Player> visiblePlayers() {
        return plugin.getServer().getAllPlayers().stream()
                .filter(this::isVisible)
                .toList();
    }

    public int visiblePlayerCount(RegisteredServer server) {
        int count = 0;
        for (Player player : server.getPlayersConnected()) {
            if (isVisible(player)) count++;
        }
        return count;
    }

    /** Applies EssentialsX vanish on the player's current backend. */
    public void applyVanish(Player player, RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        ServerConnection connection = player.getCurrentServer()
                .filter(current -> current.getServer().equals(server))
                .orElse(null);
        if (connection == null) return;

        // Hide proxy-facing surfaces before waiting for Paper to acknowledge.
        states.put(player.getUniqueId(), new Snapshot(serverName, true));
        sendVanishRequest(player, connection);
        plugin.getServer().getScheduler()
                .buildTask(plugin, () -> player.getCurrentServer()
                        .filter(current -> current.getServer().equals(server))
                        .ifPresent(current -> sendVanishRequest(player, current)))
                .delay(Duration.ofSeconds(1))
                .schedule();
    }

    private void sendVanishRequest(Player player, ServerConnection connection) {
        if (!connection.sendPluginMessage(CHANNEL, VanishBridgeProtocol.status(true))) {
            plugin.getLogger().warn("Could not request automatic vanish for {} on {}",
                    player.getUsername(), connection.getServer().getServerInfo().getName());
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection source)
                || !(event.getTarget() instanceof Player player)
                || player.getCurrentServer().filter(source::equals).isEmpty()) {
            plugin.getLogger().warn("Ignored vanish status without a matching backend player");
            return;
        }

        boolean vanished;
        try {
            vanished = VanishBridgeProtocol.decode(event.getData());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warn("Ignored malformed vanish status: {}", e.getMessage());
            return;
        }

        String serverName = source.getServer().getServerInfo().getName();
        states.put(player.getUniqueId(), new Snapshot(serverName, vanished));

        PendingSession pending = pendingSessions.get(player.getUniqueId());
        if (pending != null && pending.serverName.equals(serverName)
                && pendingSessions.remove(player.getUniqueId(), pending)) {
            plugin.getServer().getScheduler()
                    .buildTask(plugin, () -> pending.completion.accept(!vanished))
                    .schedule();
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        states.remove(playerId);
        pendingSessions.remove(playerId);
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        ServerPing ping = event.getPing();
        int visibleCount = visiblePlayers().size();
        ServerPing.Builder builder = ping.asBuilder().onlinePlayers(visibleCount);

        Collection<ServerPing.SamplePlayer> sample = ping.getPlayers()
                .map(ServerPing.Players::getSample)
                .orElse(List.of());
        List<ServerPing.SamplePlayer> filteredSample = sample.stream()
                .filter(entry -> plugin.getServer().getPlayer(entry.getId())
                        .map(this::isVisible)
                        .orElse(true))
                .toList();
        builder.samplePlayers(filteredSample);
        event.setPing(builder.build());
    }

    private record Snapshot(String serverName, boolean vanished) {
    }

    private record PendingSession(String serverName, Consumer<Boolean> completion) {
    }
}
