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
import com.velocitypowered.api.scheduler.ScheduledTask;
import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns network-wide vanish state and waits for each backend to apply it before public exposure. */
public final class VanishManager {

    public static final String PERMISSION = "crabutilities.vanish";
    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(VanishBridgeProtocol.CHANNEL);
    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(2);

    private final CrabUtilitiesVelocity plugin;
    private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PendingSession> pendingSessions = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private final Path stateFile;
    private ScheduledTask retryTask;

    public VanishManager(CrabUtilitiesVelocity plugin) {
        this.plugin = plugin;
        this.stateFile = plugin.getDataDirectory().resolve("vanished-players.txt");
        loadState();
    }

    public void start() {
        plugin.getServer().getChannelRegistrar().register(CHANNEL);
        plugin.getServer().getEventManager().register(plugin, this);
        VanishCommand.register(plugin);
        retryTask = plugin.getServer().getScheduler().buildTask(plugin, this::retryUnacknowledged)
                .repeat(Duration.ofSeconds(1)).schedule();
    }

    public void shutdown() {
        if (retryTask != null) retryTask.cancel();
        plugin.getServer().getEventManager().unregisterListener(plugin, this);
        plugin.getServer().getChannelRegistrar().unregister(CHANNEL);
        sessions.clear();
        pendingSessions.clear();
    }

    public static boolean canVanish(Player player) {
        return player.hasPermission(PERMISSION) || player.hasPermission("crabutilities.staffchat");
    }

    public boolean isVanished(Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }

    /** Returns false if the state could not be saved; in that case no change is applied. */
    public synchronized boolean setVanished(Player player, boolean vanished) {
        UUID playerId = player.getUniqueId();
        if (vanishedPlayers.contains(playerId) == vanished) return true;
        Set<UUID> updated = new HashSet<>(vanishedPlayers);
        if (vanished) updated.add(playerId);
        else updated.remove(playerId);
        try {
            saveState(updated);
        } catch (IOException e) {
            plugin.getLogger().error("Could not save vanish state for {}", player.getUsername(), e);
            return false;
        }
        if (vanished) vanishedPlayers.add(playerId);
        else vanishedPlayers.remove(playerId);
        player.getCurrentServer().ifPresent(connection -> {
            Session session = new Session(connection, revision.incrementAndGet(), vanished, false);
            sessions.put(playerId, session);
            sendState(session);
        });
        return true;
    }

    /** Applies automatic vanish only for staff authorised to use the command. */
    public boolean applyVanish(Player player, RegisteredServer server) {
        if (canVanish(player)
                && player.getCurrentServer().filter(current -> current.getServer().equals(server)).isPresent()) {
            return setVanished(player, true);
        }
        return true;
    }

    /** Starts a fresh revision on every hop, including a return to the same backend. */
    public synchronized void beginSession(Player player, RegisteredServer server, Consumer<Boolean> completion) {
        ServerConnection connection = player.getCurrentServer()
                .filter(current -> current.getServer().equals(server)).orElse(null);
        if (connection == null) return;
        UUID playerId = player.getUniqueId();
        Session session = new Session(connection, revision.incrementAndGet(), isVanished(player), false);
        sessions.put(playerId, session);
        PendingSession pending = new PendingSession(connection, completion);
        pendingSessions.put(playerId, pending);
        sendState(session);

        // Private join processing may continue after a timeout; public surfaces stay hidden until ACK.
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            if (pendingSessions.remove(playerId, pending) && isCurrent(player, connection)) {
                completion.accept(false);
            }
        }).delay(SESSION_TIMEOUT).schedule();
    }

    public boolean isVisible(Player player) {
        Session session = sessions.get(player.getUniqueId());
        ServerConnection connection = player.getCurrentServer().orElse(null);
        return !isVanished(player) && session != null && session.acknowledged
                && !session.vanished && session.connection == connection;
    }

    public List<Player> visiblePlayers() {
        return plugin.getServer().getAllPlayers().stream().filter(this::isVisible).toList();
    }

    public int visiblePlayerCount(RegisteredServer server) {
        return (int) server.getPlayersConnected().stream().filter(this::isVisible).count();
    }

    @Subscribe(order = PostOrder.EARLY)
    public synchronized void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)
                || !(event.getTarget() instanceof Player player)
                || source.getPlayer() != player || !isCurrent(player, source)) {
            return;
        }
        VanishBridgeProtocol.Message message;
        try {
            message = VanishBridgeProtocol.decode(event.getData());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warn("Ignored malformed vanish message: {}", e.getMessage());
            return;
        }

        UUID playerId = player.getUniqueId();
        Session session = sessions.get(playerId);
        if (message.ready()) {
            // Post-connect first applies automatic vanish and starts the session.
            // Its initial send also handles READY arriving before that event.
            if (session != null && session.connection == source) sendState(session);
            return;
        }
        if (session == null || session.connection != source
                || session.revision != message.revision() || session.vanished != message.vanished()) {
            return;
        }
        Session acknowledged = new Session(source, session.revision, session.vanished, true);
        sessions.put(playerId, acknowledged);
        PendingSession pending = pendingSessions.get(playerId);
        if (pending != null && pending.connection == source && pendingSessions.remove(playerId, pending)) {
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                if (isCurrent(player, source)) pending.completion.accept(isVisible(player));
            }).schedule();
        }
    }

    private static boolean isCurrent(Player player, ServerConnection connection) {
        return player.getCurrentServer().orElse(null) == connection;
    }

    private void sendState(Session session) {
        session.connection.sendPluginMessage(CHANNEL,
                VanishBridgeProtocol.status(session.revision, session.vanished));
    }

    private synchronized void retryUnacknowledged() {
        for (Session session : sessions.values()) {
            if (!session.acknowledged && isCurrent(session.connection.getPlayer(), session.connection)) {
                sendState(session);
            }
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public synchronized void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sessions.remove(playerId);
        pendingSessions.remove(playerId);
    }

    @Subscribe(order = PostOrder.LAST)
    public void onProxyPing(ProxyPingEvent event) {
        ServerPing ping = event.getPing();
        ServerPing.Builder builder = ping.asBuilder().onlinePlayers(visiblePlayers().size());
        Collection<ServerPing.SamplePlayer> sample = ping.getPlayers()
                .map(ServerPing.Players::getSample).orElse(List.of());
        List<ServerPing.SamplePlayer> filteredSample = sample.stream()
                .filter(entry -> !vanishedPlayers.contains(entry.getId()))
                .filter(entry -> plugin.getServer().getPlayer(entry.getId()).map(this::isVisible).orElse(true))
                .toList();
        event.setPing(builder.clearSamplePlayers().samplePlayers(filteredSample).build());
    }

    private void loadState() {
        try {
            if (Files.notExists(stateFile)) return;
            for (String line : Files.readAllLines(stateFile)) {
                if (!line.isBlank()) vanishedPlayers.add(UUID.fromString(line.trim()));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not load saved vanish state from " + stateFile, e);
        }
    }

    private void saveState(Set<UUID> players) throws IOException {
        Files.createDirectories(stateFile.getParent());
        Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        Files.write(temporary, players.stream().map(UUID::toString).sorted().toList());
        try {
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Session(ServerConnection connection, long revision, boolean vanished, boolean acknowledged) {
    }

    private record PendingSession(ServerConnection connection, Consumer<Boolean> completion) {
    }
}
