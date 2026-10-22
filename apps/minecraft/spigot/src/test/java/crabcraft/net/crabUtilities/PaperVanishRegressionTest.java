package crabcraft.net.crabUtilities;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.network.StatusClient;
import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PaperVanishRegressionTest {

    public static void main(String[] args) {
        pendingUntilAcknowledged();
        moderatorsAndPermissionChanges();
        independentPluginVisibilityClaims();
        joiningViewersAndMetadataOwnership();
        staleAndInvalidMessages();
        pingCountAndPlayerPreview();
    }

    private static void pendingUntilAcknowledged() {
        Fixture fixture = new Fixture();
        FakePlayer target = fixture.player("Target");
        FakePlayer normal = fixture.player("Normal");
        FakePlayer moderator = fixture.player("Moderator", "crabutilities.staffchat");
        PlayerJoinEvent join = fixture.join(target);

        check(join.joinMessage() == null, "backend join announcement was not suppressed");
        check(fixture.manager.isVanished(target.player),
                "new backend session was visible before proxy state arrived");
        check(!normal.canSee(target) && !moderator.canSee(target),
                "pending player was exposed before proxy state arrived");
        check(target.metadata.get(fixture.plugin).asBoolean(), "pending vanish metadata was missing");
        check(!target.hiddenBy.containsKey(target.id), "visibility was applied to the player themselves");

        fixture.manager.refresh();
        check(target.sent.size() == 1 && VanishBridgeProtocol.decode(target.sent.getFirst()).ready(),
                "pending session did not request authoritative proxy state");
        target.sent.clear();
        fixture.operations.clear();
        byte[] visible = VanishBridgeProtocol.status(1, false);
        fixture.receive(target, visible);

        check(!fixture.manager.isVanished(target.player),
                "visible proxy state did not clear pending vanish");
        check(normal.canSee(target) && moderator.canSee(target), "visible player remained hidden");
        check(!target.metadata.containsKey(fixture.plugin), "visible player retained vanish metadata");
        check(target.sent.size() == 1 && target.sent.getFirst() == visible,
                "acknowledgement did not preserve the exact received payload");
        check(fixture.operations.indexOf("show:Normal:Target") >= 0
                        && fixture.operations.indexOf("show:Normal:Target") < fixture.operations.indexOf("status:Target")
                        && fixture.operations.indexOf("status:Target") < fixture.operations.indexOf("send:Target"),
                "proxy state was acknowledged before visibility and status consumers were updated");
        fixture.manager.refresh();
        check(target.sent.size() == 1, "acknowledged session kept requesting proxy state");
    }

    private static void moderatorsAndPermissionChanges() {
        Fixture fixture = new Fixture();
        FakePlayer target = fixture.player("Target");
        FakePlayer normal = fixture.player("Normal");
        FakePlayer staff = fixture.player("Staff", "crabutilities.staffchat");
        FakePlayer observer = fixture.player("Observer", PaperVanishManager.SEE_PERMISSION);
        fixture.join(target);
        fixture.receive(target, VanishBridgeProtocol.status(2, true));

        check(fixture.manager.isVanished(target.player), "vanished proxy state was not applied");
        check(!normal.canSee(target) && staff.canSee(target) && observer.canSee(target),
                "vanish did not distinguish normal players and both moderator permissions");
        fixture.operations.clear();
        normal.permissions.add(PaperVanishManager.SEE_PERMISSION);
        fixture.manager.refresh();
        check(normal.canSee(target), "newly granted vanish visibility was not refreshed");
        check(fixture.operations.stream().filter("status:Target"::equals).count() == 1,
                "visibility grant did not refresh status consumers exactly once");
        fixture.operations.clear();
        fixture.manager.refresh();
        check(!fixture.operations.contains("status:Target"),
                "unchanged visibility repeatedly refreshed status consumers");
        fixture.operations.clear();
        normal.permissions.clear();
        staff.permissions.clear();
        observer.permissions.clear();
        fixture.manager.refresh();
        check(!normal.canSee(target) && !staff.canSee(target) && !observer.canSee(target),
                "revoked moderator visibility left a vanished player exposed");
        check(fixture.operations.stream().filter("status:Target"::equals).count() == 1,
                "visibility revocation did not refresh status consumers exactly once");
        fixture.operations.clear();
        fixture.manager.refresh();
        check(!fixture.operations.contains("status:Target"),
                "unchanged hidden visibility repeatedly refreshed status consumers");
    }

    private static void joiningViewersAndMetadataOwnership() {
        Fixture fixture = new Fixture();
        FakePlayer target = fixture.player("Target");
        fixture.join(target);
        fixture.receive(target, VanishBridgeProtocol.status(1, true));
        FakePlayer normal = fixture.player("NewNormal");
        FakePlayer moderator = fixture.player("NewModerator", "crabutilities.staffchat");
        fixture.join(normal);
        fixture.join(moderator);
        check(!normal.canSee(target) && moderator.canSee(target),
                "new viewers did not receive the existing player's vanish visibility");

        Plugin otherPlugin = fixture.plugin("OtherPlugin");
        target.metadata.put(otherPlugin, new FixedMetadataValue(otherPlugin, true));
        PlayerQuitEvent quit = new PlayerQuitEvent(target.player, Component.text("Left"),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        fixture.manager.onQuit(quit);
        check(quit.quitMessage() == null, "backend quit announcement was not suppressed");
        check(!target.metadata.containsKey(fixture.plugin) && target.metadata.containsKey(otherPlugin),
                "quit did not remove only this plugin's vanish metadata");
        target.sent.clear();
        fixture.manager.refresh();
        check(target.sent.size() == 1 && VanishBridgeProtocol.decode(target.sent.getFirst()).ready(),
                "quit retained the previous backend session's authoritative state");
    }

    private static void independentPluginVisibilityClaims() {
        Fixture fixture = new Fixture();
        FakePlayer target = fixture.player("Target");
        FakePlayer normal = fixture.player("Normal");
        Plugin otherPlugin = fixture.plugin("OtherPlugin");
        fixture.join(target);
        fixture.receive(target, VanishBridgeProtocol.status(1, true));
        normal.player.showPlayer(otherPlugin, target.player);
        check(!normal.canSee(target), "another plugin's showPlayer bypassed native vanish");

        normal.player.hidePlayer(otherPlugin, target.player);
        fixture.receive(target, VanishBridgeProtocol.status(2, false));
        check(!normal.canSee(target) && normal.hiddenBy.get(target.id).equals(Set.of(otherPlugin)),
                "native unvanish removed another plugin's visibility claim");
        normal.player.showPlayer(otherPlugin, target.player);
        check(normal.canSee(target), "native unvanish retained its own hidden claim");
    }

    private static void staleAndInvalidMessages() {
        Fixture fixture = new Fixture();
        FakePlayer target = fixture.player("Target");
        FakePlayer normal = fixture.player("Normal");
        fixture.join(target);
        fixture.receive(target, VanishBridgeProtocol.status(5, true));
        target.sent.clear();
        fixture.operations.clear();

        fixture.receive(target, VanishBridgeProtocol.status(4, false));
        fixture.receive(target, VanishBridgeProtocol.ready());
        fixture.receive(target, new byte[]{VanishBridgeProtocol.VERSION, 1});
        fixture.manager.onPluginMessageReceived("other:channel", target.player,
                VanishBridgeProtocol.status(6, false));
        target.online = false;
        fixture.receive(target, VanishBridgeProtocol.status(6, false));
        check(fixture.manager.isVanished(target.player) && !normal.canSee(target),
                "stale, malformed, READY or unrelated messages changed authoritative state");
        check(target.sent.isEmpty() && fixture.operations.isEmpty(),
                "ignored messages updated visibility, notified consumers or sent an acknowledgement");
    }

    private static void pingCountAndPlayerPreview() {
        Fixture fixture = new Fixture();
        FakePlayer visible = fixture.player("Visible");
        FakePlayer vanished = fixture.player("Vanished");
        FakePlayer pending = fixture.player("Pending");
        fixture.receive(visible, VanishBridgeProtocol.status(1, false));
        fixture.receive(vanished, VanishBridgeProtocol.status(1, true));
        TestPing ping = new TestPing(fixture.online);
        for (Player player : fixture.online) {
            ping.getListedPlayers().add(new PaperServerListPingEvent.ListedPlayerInfo(
                    player.getName(), player.getUniqueId()));
        }
        ping.getListedPlayers().add(new PaperServerListPingEvent.ListedPlayerInfo("Unknown", UUID.randomUUID()));
        fixture.manager.onPing(ping);
        check(ping.getNumPlayers() == 1, "ping player count included vanished or pending players");
        check(ping.getListedPlayers().equals(List.of(new PaperServerListPingEvent.ListedPlayerInfo(
                        visible.name, visible.id))),
                "ping preview included vanished, pending or unknown profiles");
    }

    private static final class Fixture {
        final List<Player> online = new ArrayList<>();
        final List<String> operations = new ArrayList<>();
        final Server server = proxy(Server.class, (instance, method, args) ->
                method.getName().equals("getOnlinePlayers") ? online : defaultValue(method.getReturnType()));
        final Plugin plugin = plugin("CrabUtilities");
        final PaperVanishManager manager = new PaperVanishManager(plugin,
                player -> operations.add("status:" + player.getName()));

        Plugin plugin(String name) {
            Logger logger = Logger.getAnonymousLogger();
            logger.setLevel(Level.OFF);
            return proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
                case "getServer" -> server;
                case "getLogger" -> logger;
                case "getName", "toString" -> name;
                case "equals" -> instance == args[0];
                case "hashCode" -> System.identityHashCode(instance);
                default -> defaultValue(method.getReturnType());
            });
        }

        FakePlayer player(String name, String... permissions) {
            FakePlayer result = new FakePlayer(this, name, permissions);
            online.add(result.player);
            return result;
        }

        PlayerJoinEvent join(FakePlayer player) {
            PlayerJoinEvent event = new PlayerJoinEvent(player.player, Component.text("Joined"));
            manager.onJoin(event);
            return event;
        }

        void receive(FakePlayer player, byte[] payload) {
            manager.onPluginMessageReceived(VanishBridgeProtocol.CHANNEL, player.player, payload);
        }
    }

    private static final class FakePlayer {
        final String name;
        final UUID id;
        final Set<String> permissions;
        final Map<UUID, Set<Plugin>> hiddenBy = new HashMap<>();
        final Map<Plugin, MetadataValue> metadata = new HashMap<>();
        final List<byte[]> sent = new ArrayList<>();
        final Player player;
        boolean online = true;

        FakePlayer(Fixture fixture, String name, String... permissions) {
            this.name = name;
            this.id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            this.permissions = new HashSet<>(List.of(permissions));
            player = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
                case "getName", "toString" -> name;
                case "getUniqueId" -> id;
                case "isOnline" -> online;
                case "hasPermission" -> this.permissions.contains(args[0]);
                case "canSee" -> !hiddenBy.containsKey(((Player) args[0]).getUniqueId());
                case "setVisibleByDefault" -> throw new AssertionError(
                        "vanish must preserve other plugins' visibility claims");
                case "showPlayer", "hidePlayer" -> {
                    Player target = (Player) args[1];
                    boolean shown = method.getName().equals("showPlayer");
                    Set<Plugin> claims = hiddenBy.computeIfAbsent(target.getUniqueId(), ignored -> new HashSet<>());
                    if (shown) claims.remove((Plugin) args[0]);
                    else claims.add((Plugin) args[0]);
                    if (claims.isEmpty()) hiddenBy.remove(target.getUniqueId());
                    fixture.operations.add((shown ? "show:" : "hide:") + name + ":" + target.getName());
                    yield null;
                }
                case "setMetadata" -> {
                    check(args[0].equals("vanished"), "unexpected metadata key");
                    MetadataValue value = (MetadataValue) args[1];
                    metadata.put(value.getOwningPlugin(), value);
                    yield null;
                }
                case "removeMetadata" -> { metadata.remove((Plugin) args[1]); yield null; }
                case "sendPluginMessage" -> {
                    check(args[0] == fixture.plugin && args[1].equals(VanishBridgeProtocol.CHANNEL),
                            "proxy message used the wrong plugin or channel");
                    sent.add((byte[]) args[2]);
                    fixture.operations.add("send:" + name);
                    yield null;
                }
                case "equals" -> instance == args[0];
                case "hashCode" -> System.identityHashCode(instance);
                default -> defaultValue(method.getReturnType());
            });
        }

        boolean canSee(FakePlayer target) {
            return player.canSee(target.player);
        }
    }

    private static final class TestPing extends PaperServerListPingEvent {
        TestPing(List<Player> players) {
            super(proxy(StatusClient.class, (instance, method, args) ->
                            method.getName().equals("getAddress") ? new InetSocketAddress("127.0.0.1", 25565)
                                    : defaultValue(method.getReturnType())),
                    Component.empty(), players.size(), 100, "Test", 0, null);
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unsupported primitive: " + type);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
