package crabcraft.net.crabUtilities.velocity.tags;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.zaxxer.hikari.HikariDataSource;
import crabcraft.net.crabUtilities.tags.TagBridgeProtocol;
import crabcraft.net.crabUtilities.tags.TagBridgeProtocol.Status;
import crabcraft.net.crabUtilities.tags.TagDefinition;
import crabcraft.net.crabUtilities.tags.TagSnapshot;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.db.TagRepository;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class TagServiceRegressionTest {
    public static void main(String[] args) throws Exception {
        monotonicSnapshotsAndCatalogueRefresh();
        rejectedExecutorIsUnavailable();
        databaseFailuresNeverAcknowledgeSuccess();
        bridgeRejectsUntrustedAndMalformedPackets();
        backendTransfersDiscardOldRequests();
        quickRelogDoesNotAdoptOldWork();
        oneMutationAndBoundedQueue();
    }

    private static void monotonicSnapshotsAndCatalogueRefresh() {
        UUID id = UUID.randomUUID();
        TagSession session = new TagSession(null, id);
        var tag = new TagDefinition("pumpkin", "Halloween", "Earned", "pumpkin", 1, true);
        TagSnapshot latest = new TagSnapshot(id, 6, "pumpkin", List.of(tag));
        check(session.accept(latest) == latest, "confirmed snapshot was not cached");
        check(session.accept(new TagSnapshot(id, 5, null, List.of())) == latest,
                "a late query restored an old selection");
        var disabled = new TagDefinition("pumpkin", "Updated name", "Earned", "pumpkin", 1, false);
        TagSnapshot metadata = new TagSnapshot(id, 6, "pumpkin", List.of(disabled));
        check(session.accept(metadata) == metadata, "equal revision blocked catalogue changes");
        try {
            session.accept(new TagSnapshot(UUID.randomUUID(), 7, null, List.of()));
            throw new AssertionError("another player's snapshot was accepted");
        } catch (IllegalArgumentException expected) {}
    }

    private static void rejectedExecutorIsUnavailable() throws Exception {
        Fixture f = new Fixture();
        f.plugin.acceptTasks = false;
        f.query();
        check(f.messages.size() == 1 && f.messages.getFirst().status() == Status.UNAVAILABLE,
                "queue rejection did not return Unavailable");
        check(f.database.calls == 0, "a rejected request touched PostgreSQL");
    }

    private static void databaseFailuresNeverAcknowledgeSuccess() throws Exception {
        Fixture f = new Fixture();
        f.query();
        check(f.messages.isEmpty(), "request was acknowledged before database work");
        f.runAll();
        f.select();
        f.runAll();
        check(f.messages.size() == 2 && f.messages.stream().allMatch(packet -> packet.status() == Status.UNAVAILABLE),
                "database failure produced a success acknowledgement");
        check(f.messages.stream().allMatch(packet -> f.id.equals(packet.playerId()) && packet.snapshot() == null),
                "unavailable response lost the authenticated actor or invented an empty snapshot");
    }

    private static void bridgeRejectsUntrustedAndMalformedPackets() throws Exception {
        Fixture f = new Fixture();
        VelocityTagBridge bridge = new VelocityTagBridge(f.plugin, f.service);
        PluginMessageEvent client = f.event(f.player, f.backend, TagBridgeProtocol.query(UUID.randomUUID()));
        bridge.onPluginMessage(client);
        check(!client.getResult().isAllowed(), "client tag packet was forwarded");
        ServerConnection stranger = f.backend();
        PluginMessageEvent wrongBackend = f.event(stranger, f.player, TagBridgeProtocol.select(UUID.randomUUID(), "pumpkin", 0));
        bridge.onPluginMessage(wrongBackend);
        PluginMessageEvent malformed = f.event(f.backend, f.player, new byte[]{1, 1});
        bridge.onPluginMessage(malformed);
        PluginMessageEvent response = f.event(f.backend, f.player,
                TagBridgeProtocol.state(new TagSnapshot(f.id, 0, null, List.of())));
        bridge.onPluginMessage(response);
        check(!wrongBackend.getResult().isAllowed() && !malformed.getResult().isAllowed()
                && !response.getResult().isAllowed(), "rejected tag packets were forwarded");
        check(f.plugin.tasks.isEmpty() && f.database.calls == 0, "untrusted packet reached database work");
        bridge.onPluginMessage(f.event(f.backend, f.player, TagBridgeProtocol.query(UUID.randomUUID())));
        check(f.plugin.tasks.size() == 1, "active authenticated backend could not query");
    }

    private static void backendTransfersDiscardOldRequests() throws Exception {
        Fixture f = new Fixture();
        f.select();
        f.backend = f.backend();
        f.runAll();
        check(f.database.calls == 0 && f.messages.isEmpty(), "a queued request from the old backend wrote or replied");
        f.query();
        f.runAll();
        check(f.messages.size() == 1, "the current backend could not query after transfer");
    }

    private static void quickRelogDoesNotAdoptOldWork() throws Exception {
        Fixture f = new Fixture();
        f.query();
        Player previous = f.player;
        f.service.onDisconnect(new DisconnectEvent(previous, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        f.player = f.player();
        f.query();
        // A delayed disconnect event must not evict the new login's session.
        f.service.onDisconnect(new DisconnectEvent(previous, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        f.runAll();
        check(f.database.calls == 1 && f.messages.size() == 1,
                "a quick relog adopted old work or was removed by the old disconnect");
    }

    private static void oneMutationAndBoundedQueue() throws Exception {
        Fixture f = new Fixture();
        f.select();
        f.select();
        check(f.messages.size() == 1 && f.messages.getFirst().status() == Status.UNAVAILABLE,
                "two mutations were allowed in flight");
        for (int i = 0; i < 20; i++) f.query();
        check(f.plugin.tasks.size() == 1, "per-player requests escaped their serial worker");
        f.runAll();
        check(f.database.calls <= TagSession.MAX_PENDING, "per-player request queue was unbounded");
        check(f.messages.stream().noneMatch(packet -> packet.status() == Status.OK), "rejection acknowledged success");
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final List<TagBridgeProtocol.Packet> messages = new ArrayList<>();
        final FailingDataSource database = new FailingDataSource();
        ServerConnection backend = backend();
        Player player = player();
        final ProxyServer server = mock(ProxyServer.class, (proxy, method, args) -> switch (method.getName()) {
            case "getPlayer" -> Optional.ofNullable(player);
            case "getAllPlayers" -> List.of(player);
            default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
        });
        final TestPlugin plugin = new TestPlugin(server);
        final TagService service;

        Fixture() throws SQLException {
            TagRepository repository = new TagRepository(database, plugin.getLogger());
            database.fail = true;
            service = new TagService(plugin, repository, null);
        }

        void query() { service.request(player, backend, TagBridgeProtocol.decode(TagBridgeProtocol.query(UUID.randomUUID()))); }
        void select() { service.request(player, backend, TagBridgeProtocol.decode(TagBridgeProtocol.select(UUID.randomUUID(), "pumpkin", 0))); }
        void runAll() { while (!plugin.tasks.isEmpty()) plugin.tasks.remove().run(); }

        Player player() {
            return mock(Player.class, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getUsername" -> "ExamplePlayer";
                case "getCurrentServer" -> Optional.ofNullable(backend);
                case "isActive" -> true;
                default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
            });
        }

        ServerConnection backend() {
            return mock(ServerConnection.class, (proxy, method, args) -> {
                if (method.getName().equals("sendPluginMessage")) {
                    messages.add(TagBridgeProtocol.decode((byte[]) args[1]));
                    return true;
                }
                return defaultValue(proxy, method.getName(), method.getReturnType(), args);
            });
        }

        PluginMessageEvent event(com.velocitypowered.api.proxy.messages.ChannelMessageSource source,
                                 com.velocitypowered.api.proxy.messages.ChannelMessageSink target, byte[] payload) {
            return new PluginMessageEvent(source, target, MinecraftChannelIdentifier.from(TagBridgeProtocol.CHANNEL), payload);
        }
    }

    private static final class TestPlugin extends CrabUtilitiesVelocity {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean acceptTasks = true;

        TestPlugin(ProxyServer server) {
            super(server, mock(Logger.class, (proxy, method, args) ->
                    defaultValue(proxy, method.getName(), method.getReturnType(), args)), Path.of("."));
        }

        @Override
        public boolean runDatabaseTask(String name, Runnable task) {
            if (acceptTasks) tasks.add(task);
            return acceptTasks;
        }
    }

    /** Initialises the real repository, then fails all request-time connections. */
    private static final class FailingDataSource extends HikariDataSource {
        boolean fail;
        int calls;

        @Override
        public Connection getConnection() throws SQLException {
            if (fail) { calls++; throw new SQLException("deliberate test outage"); }
            return mock(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "createStatement" -> mock(Statement.class, (statement, call, values) ->
                        defaultValue(statement, call.getName(), call.getReturnType(), values));
                case "prepareStatement" -> mock(PreparedStatement.class, (statement, call, values) ->
                        call.getName().equals("executeBatch") ? new int[]{1}
                                : defaultValue(statement, call.getName(), call.getReturnType(), values));
                default -> defaultValue(proxy, method.getName(), method.getReturnType(), args);
            });
        }
    }

    private static <T> T mock(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Object proxy, String name, Class<?> type, Object[] args) {
        if (name.equals("equals")) return proxy == args[0];
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("toString")) return "test proxy";
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
