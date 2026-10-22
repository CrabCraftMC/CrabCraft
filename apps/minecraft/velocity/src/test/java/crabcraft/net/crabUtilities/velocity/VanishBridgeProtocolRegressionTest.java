package crabcraft.net.crabUtilities.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class VanishBridgeProtocolRegressionTest {

    public static void main(String[] args) throws IOException {
        validatesProtocol();
        waitsForMatchingAcknowledgementAndReplaysReady();
        ignoresStaleAcknowledgementsAfterToggleAndTransfer();
        preservesStateAcrossReconnectAndRestart();
        filtersPingSamplesAndCounts();
        guardsAutomaticVanishAndFailedSaves();
        check(ConnectionListener.isSilentJoinHost("Mods.CrabCraft.Net.", List.of("mods.crabcraft.net")),
                "silent join hostname was not normalised");
        check(!ConnectionListener.isSilentJoinHost("crabcraft.net", List.of("mods.crabcraft.net")),
                "ordinary hostname was treated as silent");
    }

    private static void validatesProtocol() {
        check(VanishBridgeProtocol.decode(VanishBridgeProtocol.ready()).ready(), "READY did not round-trip");
        for (boolean vanished : new boolean[]{false, true}) {
            VanishBridgeProtocol.Message decoded = VanishBridgeProtocol.decode(
                    VanishBridgeProtocol.status(Long.MAX_VALUE, vanished));
            check(!decoded.ready() && decoded.revision() == Long.MAX_VALUE && decoded.vanished() == vanished,
                    "state or revision did not round-trip");
        }
        rejects(null);
        rejects(new byte[]{2});
        rejects(new byte[]{1, 0});
        rejects(new byte[]{2, 2});
        rejects(new byte[]{2, 0, 0});
        rejects(new byte[]{2, 1});
        byte[] invalidBoolean = VanishBridgeProtocol.status(1, false);
        invalidBoolean[10] = 2;
        rejects(invalidBoolean);
        byte[] negativeRevision = VanishBridgeProtocol.status(1, false);
        negativeRevision[2] = (byte) 0xff;
        rejects(negativeRevision);
        byte[] extraByte = java.util.Arrays.copyOf(VanishBridgeProtocol.status(1, false), 12);
        rejects(extraByte);
        try {
            VanishBridgeProtocol.status(-1, true);
            throw new AssertionError("negative outbound revision was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void waitsForMatchingAcknowledgementAndReplaysReady() throws IOException {
        try (Harness h = new Harness()) {
            check(!h.manager.isVisible(h.player), "unknown backend was public");
            // Paper can announce readiness before the proxy post-connect listener runs.
            h.message(h.first, VanishBridgeProtocol.ready());
            check(h.first.payloads.isEmpty(), "early READY bypassed initial automatic vanish processing");
            h.begin(h.first);
            byte[] current = h.first.lastPayload();
            h.message(h.first, VanishBridgeProtocol.status(revision(current) - 1, false));
            check(!h.manager.isVisible(h.player), "pre-session ACK exposed the player");
            h.message(h.first, VanishBridgeProtocol.ready());
            check(java.util.Arrays.equals(current, h.first.lastPayload()), "READY changed the desired revision");
            h.message(h.first, current);
            check(h.manager.isVisible(h.player), "matching visible ACK remained hidden");
            check(h.completions.equals(List.of(true)), "join completion did not use acknowledged state");
            h.message(h.first, current);
            check(h.completions.size() == 1, "duplicate ACK completed the join twice");
            PluginMessageEvent spoof = new PluginMessageEvent(h.player, h.first.connection,
                    MinecraftChannelIdentifier.from(VanishBridgeProtocol.CHANNEL), current);
            h.manager.onPluginMessage(spoof);
            check(!spoof.getResult().isAllowed(), "client plugin message was forwarded");
        }
    }

    private static void ignoresStaleAcknowledgementsAfterToggleAndTransfer() throws IOException {
        try (Harness h = new Harness()) {
            h.begin(h.first);
            byte[] visible = h.first.lastPayload();
            h.message(h.first, visible);
            check(h.manager.setVanished(h.player, true), "vanish did not save");
            byte[] hidden = h.first.lastPayload();
            check(!h.manager.isVisible(h.player), "vanish toggle waited for ACK before hiding public surfaces");
            h.message(h.first, visible);
            check(h.manager.isVanished(h.player) && !h.manager.isVisible(h.player), "stale ACK cleared vanish");
            h.message(h.first, VanishBridgeProtocol.status(revision(hidden), false));
            check(!h.manager.isVisible(h.player), "wrong-state ACK was accepted");
            check(h.manager.setVanished(h.player, false), "unvanish did not save");
            byte[] unhidden = h.first.lastPayload();
            check(!h.manager.isVisible(h.player), "unvanish exposed player before Paper applied state");
            h.message(h.first, hidden);
            check(!h.manager.isVanished(h.player) && !h.manager.isVisible(h.player), "stale vanish ACK was authoritative");
            h.message(h.first, unhidden);
            check(h.manager.isVisible(h.player), "unvanish ACK was not applied");
            h.current = h.second;
            check(!h.manager.isVisible(h.player), "player stayed public before the new backend ACK");
            h.begin(h.second);
            byte[] secondState = h.second.lastPayload();
            h.message(h.first, unhidden);
            h.message(h.second, unhidden);
            check(!h.manager.isVisible(h.player), "old backend or revision ACK exposed a transferred player");
            h.message(h.second, secondState);
            check(h.manager.isVisible(h.player), "current backend ACK did not expose visible player");
            h.current = h.first;
            h.begin(h.first);
            h.message(h.first, unhidden);
            check(!h.manager.isVisible(h.player), "returning to the same backend accepted an old ACK");
            h.message(h.first, h.first.lastPayload());
            check(h.manager.isVisible(h.player), "return hop failed to apply a fresh revision");
        }
    }

    private static void preservesStateAcrossReconnectAndRestart() throws IOException {
        try (Harness h = new Harness()) {
            h.begin(h.first);
            check(h.manager.setVanished(h.player, true), "vanish state did not save");
            h.manager.onDisconnect(new com.velocitypowered.api.event.connection.DisconnectEvent(
                    h.player, com.velocitypowered.api.event.connection.DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
            check(h.manager.isVanished(h.player), "disconnect forgot authoritative vanish state");
            h.begin(h.second);
            check(VanishBridgeProtocol.decode(h.second.lastPayload()).vanished(), "backend swap lost vanish");
            VanishManager restarted = new VanishManager(h.plugin);
            check(restarted.isVanished(h.player), "proxy restart lost saved vanish");
            restarted.beginSession(h.player, h.second.server, ignored -> { });
            check(VanishBridgeProtocol.decode(h.second.lastPayload()).vanished(), "reconnect did not restore vanish");
        }
    }

    private static void filtersPingSamplesAndCounts() throws IOException {
        try (Harness h = new Harness()) {
            h.begin(h.first);
            UUID decorativeId = UUID.randomUUID();
            check(h.ping(decorativeId).getPlayers().orElseThrow().getOnline() == 0,
                    "unacknowledged player was included in ping count");
            h.message(h.first, h.first.lastPayload());
            check(h.ping(decorativeId).getPlayers().orElseThrow().getOnline() == 1,
                    "visible player was missing from ping count");
            h.manager.setVanished(h.player, true);
            h.online = false;
            ServerPing.Players players = h.ping(decorativeId).getPlayers().orElseThrow();
            check(players.getOnline() == 0, "vanished player leaked into ping count");
            check(players.getSample().size() == 1 && players.getSample().getFirst().getId().equals(decorativeId),
                    "saved vanished player leaked from stale ping samples or decorative sample was removed");
        }
    }

    private static void guardsAutomaticVanishAndFailedSaves() throws IOException {
        try (Harness h = new Harness()) {
            h.begin(h.first);
            h.manager.applyVanish(h.player, h.first.server);
            check(!h.manager.isVanished(h.player), "hostname granted vanish to an unauthorised player");
            h.permissions.add("crabutilities.staffchat");
            h.manager.applyVanish(h.player, h.first.server);
            check(h.manager.isVanished(h.player), "existing moderator permission did not allow vanish");
            h.permissions.clear();
            h.permissions.add("crabutilities.vanish");
            check(VanishManager.canVanish(h.player), "explicit vanish permission was ignored");
            Path stateFile = h.directory.resolve("vanished-players.txt");
            Files.delete(stateFile);
            Files.createDirectory(stateFile);
            Files.writeString(stateFile.resolve("block-replacement"), "test");
            check(!h.manager.setVanished(h.player, false), "failed save was reported as successful");
            check(h.manager.isVanished(h.player), "failed save changed authoritative state");
        }
        try (Harness h = new Harness()) {
            h.permissions.add("crabutilities.staffchat");
            Path stateFile = h.directory.resolve("vanished-players.txt");
            Files.createDirectory(stateFile);
            Files.writeString(stateFile.resolve("block-replacement"), "test");
            check(!h.manager.applyVanish(h.player, h.first.server),
                    "automatic vanish save failure did not tell the connection listener to disconnect");
            check(h.first.payloads.isEmpty() && !h.manager.isVisible(h.player),
                    "failed automatic vanish sent a visible state before disconnect");
        }
    }

    private static long revision(byte[] payload) {
        return VanishBridgeProtocol.decode(payload).revision();
    }

    private static void rejects(byte[] payload) {
        try {
            VanishBridgeProtocol.decode(payload);
            throw new AssertionError("malformed vanish payload was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("toString")) return type.getSimpleName() + " test stub";
            return handler.invoke(proxy, method, args);
        });
    }

    private static final class Harness implements AutoCloseable {
        private final Path directory = Files.createTempDirectory("crabcraft-vanish-test-");
        private final UUID playerId = UUID.randomUUID();
        private final Set<String> permissions = new HashSet<>();
        private final List<Boolean> completions = new ArrayList<>();
        private final Player player;
        private final CrabUtilitiesVelocity plugin;
        private final VanishManager manager;
        private final Backend first;
        private final Backend second;
        private Backend current;
        private boolean online = true;

        private Harness() throws IOException {
            player = stub(Player.class, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "getUsername" -> "Moderator";
                case "getCurrentServer" -> Optional.ofNullable(current).map(backend -> backend.connection);
                case "hasPermission" -> permissions.contains(args[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
            first = new Backend("survival");
            second = new Backend("creative");
            current = first;
            Scheduler scheduler = stub(Scheduler.class, (proxy, method, args) -> {
                if (!method.getName().equals("buildTask")) throw new UnsupportedOperationException(method.getName());
                Runnable task = (Runnable) args[1];
                boolean[] delayed = {false};
                return stub(Scheduler.TaskBuilder.class, (builder, builderMethod, builderArgs) -> {
                    if (builderMethod.getName().equals("schedule")) {
                        if (!delayed[0]) task.run();
                        return stub(ScheduledTask.class, (ignored, ignoredMethod, ignoredArgs) -> null);
                    }
                    if (builderMethod.getName().equals("delay") || builderMethod.getName().equals("repeat")) {
                        delayed[0] = true;
                        return builder;
                    }
                    throw new UnsupportedOperationException(builderMethod.getName());
                });
            });
            ProxyServer proxyServer = stub(ProxyServer.class, (proxy, method, args) -> switch (method.getName()) {
                case "getScheduler" -> scheduler;
                case "getAllPlayers" -> online ? List.of(player) : List.of();
                case "getPlayer" -> online && playerId.equals(args[0]) ? Optional.of(player) : Optional.empty();
                default -> throw new UnsupportedOperationException(method.getName());
            });
            plugin = new CrabUtilitiesVelocity(proxyServer, stub(Logger.class, (proxy, method, args) -> null), directory);
            manager = new VanishManager(plugin);
        }

        private void begin(Backend backend) {
            current = backend;
            manager.beginSession(player, backend.server, completions::add);
        }

        private void message(Backend backend, byte[] payload) {
            manager.onPluginMessage(new PluginMessageEvent(backend.connection, player,
                    MinecraftChannelIdentifier.from(VanishBridgeProtocol.CHANNEL), payload));
        }

        private ServerPing ping(UUID decorativeId) {
            ServerPing ping = ServerPing.builder()
                    .version(new ServerPing.Version(999, "test"))
                    .description(Component.text("test"))
                    .maximumPlayers(20).onlinePlayers(1)
                    .samplePlayers(new ServerPing.SamplePlayer("Moderator", playerId),
                            new ServerPing.SamplePlayer("Welcome", decorativeId)).build();
            ProxyPingEvent event = new ProxyPingEvent(player, ping);
            manager.onProxyPing(event);
            return event.getPing();
        }

        @Override
        public void close() throws IOException {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }

        private final class Backend {
            private final RegisteredServer server;
            private final ServerConnection connection;
            private final List<byte[]> payloads = new ArrayList<>();

            private Backend(String name) {
                ServerInfo info = new ServerInfo(name, new InetSocketAddress("localhost", 25565));
                server = stub(RegisteredServer.class, (proxy, method, args) -> switch (method.getName()) {
                    case "getServerInfo" -> info;
                    case "getPlayersConnected" -> current == this && online ? List.of(player) : List.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
                connection = stub(ServerConnection.class, (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getPlayer" -> player;
                    case "sendPluginMessage" -> {
                        payloads.add(((byte[]) args[1]).clone());
                        yield true;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
            }

            private byte[] lastPayload() {
                return payloads.getLast();
            }
        }
    }
}
