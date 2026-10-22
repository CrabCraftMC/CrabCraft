package crabcraft.net.crabUtilities.tags;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.logging.Logger;

/** Exercises the real request lifecycle without a running Minecraft server. */
final class PaperTagServiceRegressionTest {
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final TagDefinition PUMPKIN = new TagDefinition("halloween-2026-pumpkin", "Halloween 2026",
            "Complete the challenges.", "pumpkin", 10, true);
    private static final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private static final List<Runnable> timeouts = new ArrayList<>();
    private static final List<byte[]> sent = new ArrayList<>();
    private static Player online;
    private static boolean primary = true;

    public static void main(String[] args) throws ReflectiveOperationException {
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (method, values) -> {
            if (method.equals("runTask")) tasks.add((Runnable) values[1]);
            if (method.equals("runTaskLater")) timeouts.add((Runnable) values[1]);
            return proxy(BukkitTask.class, (ignored, unused) -> null);
        });
        Messenger messenger = proxy(Messenger.class, (method, values) -> null);
        PluginManager pluginManager = proxy(PluginManager.class, (method, values) -> null);
        Server server = proxy(Server.class, (method, values) -> switch (method) {
            case "getScheduler" -> scheduler;
            case "getPluginManager" -> pluginManager;
            case "getMessenger" -> messenger;
            case "getPlayer" -> PLAYER_ID.equals(values[0]) ? online : null;
            case "getOnlinePlayers" -> online == null ? List.of() : List.of(online);
            case "isPrimaryThread" -> primary;
            case "getLogger" -> Logger.getAnonymousLogger();
            case "getName", "getVersion", "getBukkitVersion" -> "tags-regression";
            default -> null;
        });
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
        Plugin plugin = proxy(Plugin.class, (method, values) -> switch (method) {
            case "getServer" -> server;
            case "getLogger" -> Logger.getAnonymousLogger();
            default -> null;
        });
        PaperTagService service = new PaperTagService(plugin, glyph -> Component.text("ꔌ"));
        online = player(PLAYER_ID);
        service.start();
        List<PaperTagService.Response> replies = new ArrayList<>();

        service.query(online, replies::add);
        UUID query = lastRequest().requestId();
        deliver(service, online, UUID.randomUUID(), PLAYER_ID, 1, PUMPKIN.id());
        deliver(service, online, query, UUID.randomUUID(), 1, PUMPKIN.id());
        deliver(service, player(UUID.randomUUID()), query, PLAYER_ID, 1, PUMPKIN.id());
        check(replies.isEmpty() && service.cache().get(PLAYER_ID) == null,
                "unmatched actor/carrier/request updated state or completed a query");

        primary = false;
        deliver(service, online, query, PLAYER_ID, 1, null);
        check(replies.isEmpty(), "network callback touched Bukkit state before the main thread");
        primary = true;
        while (!tasks.isEmpty()) tasks.remove().run();
        check(replies.size() == 1 && service.cache().placeholder(PLAYER_ID).isEmpty(),
                "acknowledged query did not complete on the main thread");
        deliver(service, online, query, PLAYER_ID, 2, PUMPKIN.id());
        check(replies.size() == 1 && service.cache().get(PLAYER_ID).snapshot().revision() == 1,
                "duplicate completed reply changed cached state");

        service.select(online, PUMPKIN.id(), 1, replies::add);
        UUID select = lastRequest().requestId();
        check(service.isSelecting(PLAYER_ID) && service.cache().placeholder(PLAYER_ID).isEmpty(),
                "selection was applied before database acknowledgement");
        int messages = sent.size();
        List<PaperTagService.Response> rejected = new ArrayList<>();
        service.select(online, null, 1, rejected::add);
        service.query(online, rejected::add);
        check(sent.size() == messages && rejected.size() == 2 && service.isSelecting(PLAYER_ID),
                "another request replaced an outstanding selection");
        deliver(service, online, select, PLAYER_ID, 2, PUMPKIN.id());
        check(replies.size() == 2 && !service.isSelecting(PLAYER_ID)
                        && "<white><glyph:pumpkin></white>".equals(service.cache().placeholder(PLAYER_ID)),
                "committed selection was not published");

        service.select(online, null, 2, replies::add);
        UUID timedOut = lastRequest().requestId();
        timeouts.getLast().run();
        check(replies.getLast().timedOut() && !service.isSelecting(PLAYER_ID), "timeout retained a pending selection");
        int completed = replies.size();
        deliver(service, online, timedOut, PLAYER_ID, 3, null);
        check(replies.size() == completed && !service.cache().placeholder(PLAYER_ID).isEmpty(),
                "late timed-out reply silently reported success or changed state");

        service.query(online, replies::add);
        UUID staleSession = lastRequest().requestId();
        Player departed = online;
        service.onQuit(new PlayerQuitEvent(departed, (Component) null));
        online = player(PLAYER_ID);
        service.query(online, replies::add);
        UUID currentSession = lastRequest().requestId();
        deliver(service, departed, staleSession, PLAYER_ID, 10, PUMPKIN.id());
        deliver(service, online, staleSession, PLAYER_ID, 10, PUMPKIN.id());
        check(service.cache().get(PLAYER_ID) == null && replies.size() == completed,
                "previous session reply affected a reconnected player");
        deliver(service, online, currentSession, PLAYER_ID, 3, null);
        check(replies.size() == completed + 1, "new session's query did not complete");

        service.onPluginMessageReceived(TagBridgeProtocol.CHANNEL, online,
                TagBridgeProtocol.state(new TagSnapshot(PLAYER_ID, 4, PUMPKIN.id(), List.of(PUMPKIN))));
        check(!service.cache().placeholder(PLAYER_ID).isEmpty(), "committed state push did not update the cache");
        service.query(online, replies::add);
        Runnable pendingTimeout = timeouts.getLast();
        service.shutdown();
        completed = replies.size();
        pendingTimeout.run();
        check(service.cache().get(PLAYER_ID) == null && replies.size() == completed,
                "shutdown left a cache entry or a live request callback");
    }

    private static void deliver(PaperTagService service, Player carrier, UUID request, UUID actor,
                                long revision, String selected) {
        service.onPluginMessageReceived(TagBridgeProtocol.CHANNEL, carrier, TagBridgeProtocol.result(
                request, actor, TagBridgeProtocol.Status.OK, new TagSnapshot(actor, revision, selected, List.of(PUMPKIN))));
    }

    private static TagBridgeProtocol.Packet lastRequest() {
        return TagBridgeProtocol.decode(sent.getLast());
    }

    private static Player player(UUID id) {
        return proxy(Player.class, (method, values) -> switch (method) {
            case "getUniqueId" -> id;
            case "isOnline" -> true;
            case "sendPluginMessage" -> {
                sent.add((byte[]) values[2]);
                yield null;
            }
            default -> null;
        });
    }

    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, values) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == values[0];
                    case "toString" -> type.getSimpleName();
                    default -> handler.apply(method.getName(), values);
                }));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
