package crabcraft.net.crabUtilities.afk;

import crabcraft.net.crabUtilities.chat.bridge.PaperChatBridge;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class AfkRegressionTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final long FIVE_MINUTES = Duration.ofMinutes(5).toNanos();

    public static void main(String[] args) throws ReflectiveOperationException {
        AfkTracker tracker = new AfkTracker(FIVE_MINUTES);
        tracker.add(PLAYER, 0L);
        check(!tracker.markIdle(PLAYER, FIVE_MINUTES - 1L), "AFK began before five idle minutes");
        check(tracker.markIdle(PLAYER, FIVE_MINUTES), "AFK did not begin after five idle minutes");
        check(tracker.isAfk(PLAYER), "the placeholder state did not reflect automatic AFK");
        check(!tracker.markIdle(PLAYER, FIVE_MINUTES + 1L), "idle polling repeated the AFK transition");
        check(tracker.recordActivity(PLAYER, FIVE_MINUTES + 2L), "activity did not clear automatic AFK");
        check(!tracker.isAfk(PLAYER), "AFK stayed set after activity");
        check(!tracker.recordActivity(PLAYER, FIVE_MINUTES + 3L), "ordinary activity reported an AFK transition");
        check(!tracker.markIdle(PLAYER, 2L * FIVE_MINUTES), "activity did not restart the idle timeout");

        check(tracker.toggle(PLAYER, 2L * FIVE_MINUTES), "manual /afk did not enable AFK");
        check(!tracker.toggle(PLAYER, 2L * FIVE_MINUTES + 1L), "/afk did not toggle AFK off");
        check(!tracker.markIdle(PLAYER, 3L * FIVE_MINUTES), "leaving manual AFK immediately marked AFK again");
        check(tracker.toggle(PLAYER, 3L * FIVE_MINUTES), "manual AFK could not be re-enabled");
        check(tracker.recordActivity(PLAYER, 3L * FIVE_MINUTES + 1L), "movement did not clear manual AFK");

        tracker.toggle(PLAYER, 4L * FIVE_MINUTES);
        tracker.remove(PLAYER);
        check(!tracker.isAfk(PLAYER), "disconnected players stayed AFK");
        check(!tracker.recordActivity(PLAYER, 4L * FIVE_MINUTES), "delayed chat resurrected disconnected state");
        tracker.add(PLAYER, 4L * FIVE_MINUTES);
        check(!tracker.isAfk(PLAYER), "rejoining restored old AFK state");
        tracker.clear();
        check(!tracker.isAfk(PLAYER), "shutdown retained AFK state");

        check(AfkManager.isAfkCommand("/afk"), "AFK preprocessing broke the command toggle");
        check(AfkManager.isAfkCommand("/CrabUtilities:AFK"), "namespaced AFK did not preserve its toggle");
        check(!AfkManager.isAfkCommand("/msg Afk hello"), "an ordinary command was treated as /afk");

        checkBridgedActivity();
    }

    private static void checkBridgedActivity() throws ReflectiveOperationException {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        try {
            BridgeFixture silent = new BridgeFixture();
            silent.tracker.add(PLAYER, System.nanoTime());
            silent.manager.onCommand(silent.player, null, "afk", new String[0]);
            check(silent.manager.isAfk(PLAYER), "/afk did not enable AFK silently");
            silent.manager.onCommand(silent.player, null, "afk", new String[0]);
            check(!silent.manager.isAfk(PLAYER), "/afk did not disable AFK silently");
            silent.tracker.add(PLAYER, System.nanoTime() - FIVE_MINUTES);
            var checkIdle = AfkManager.class.getDeclaredMethod("checkIdle");
            checkIdle.setAccessible(true);
            checkIdle.invoke(silent.manager);
            check(silent.manager.isAfk(PLAYER), "idle polling did not enable AFK silently");
            silent.manager.recordActivity(silent.player);
            check(!silent.manager.isAfk(PLAYER), "activity did not clear AFK silently");
            check(silent.messages == 0, "AFK transitions sent chat or console announcements");

            for (boolean manual : List.of(false, true)) {
                for (String command : List.of("/msg Alex hello", "/r hello", "/sc hello")) {
                    BridgeFixture fixture = new BridgeFixture();
                    fixture.markAfk(manual);
                    PlayerCommandPreprocessEvent event = fixture.command(command, false);
                    check(event.isCancelled(), command + " was not intercepted by the bridge");
                    check(fixture.sent == 1 && fixture.activities == 1,
                            command + " did not record exactly one accepted communication");
                    fixture.checkReturned(command);
                }

                BridgeFixture fixture = new BridgeFixture();
                fixture.markAfk(manual);
                fixture.primaryThread = false;
                AsyncChatEvent event = fixture.staffChat("# hello", false);
                check(event.isCancelled(), "staff-prefix chat was not intercepted");
                check(fixture.sent == 0 && fixture.activities == 0 && fixture.tasks.size() == 1,
                        "asynchronous staff chat recorded activity before its main-thread send");
                check(fixture.manager.isAfk(PLAYER), "queued staff chat cleared AFK off the main thread");
                fixture.primaryThread = true;
                fixture.tasks.remove().run();
                check(fixture.sent == 1 && fixture.activities == 1,
                        "scheduled staff chat did not record one accepted communication");
                fixture.checkReturned("staff-prefix chat");
            }

            BridgeFixture rejected = new BridgeFixture();
            rejected.markAfk(true);
            rejected.command("/msg Alex", false);
            rejected.command("/r", false);
            rejected.command("/sc", false);
            rejected.command("/msg Alex already cancelled", true);
            rejected.command("/r already cancelled", true);
            rejected.command("/sc already cancelled", true);
            rejected.staffChat("# already cancelled", true);
            rejected.staffPermission = false;
            rejected.command("/sc denied", false);
            rejected.staffChat("# not a staff member", false);
            rejected.staffPermission = true;
            String oversized = "x".repeat(28_001);
            rejected.command("/msg Alex " + oversized, false);
            rejected.command("/r " + oversized, false);
            rejected.command("/sc " + oversized, false);
            rejected.staffChat("# " + oversized, false);
            rejected.online = false;
            rejected.command("/msg Alex disconnected", false);
            rejected.checkNoActivity();

            BridgeFixture disconnecting = new BridgeFixture();
            disconnecting.markAfk(false);
            disconnecting.primaryThread = false;
            disconnecting.staffChat("# disconnect before send", false);
            disconnecting.online = false;
            disconnecting.primaryThread = true;
            disconnecting.tasks.remove().run();
            disconnecting.checkNoActivity();
        } finally {
            serverField.set(null, previousServer);
        }
    }

    /** Applies Bukkit's cancellation rule when invoking the actual listener methods. */
    private static void dispatch(Object listener, String methodName, Event event)
            throws ReflectiveOperationException {
        var method = listener.getClass().getMethod(methodName, event.getClass());
        EventHandler handler = method.getAnnotation(EventHandler.class);
        if (handler.ignoreCancelled() && event instanceof Cancellable cancellable
                && cancellable.isCancelled()) return;
        method.invoke(listener, event);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class BridgeFixture {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final AfkManager manager;
        final AfkTracker tracker;
        final PaperChatBridge bridge;
        final Player player;
        long beforeSend;
        boolean primaryThread = true;
        boolean staffPermission = true;
        boolean online = true;
        int sent;
        int activities;
        int messages;

        BridgeFixture() throws ReflectiveOperationException {
            player = proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> PLAYER;
                case "hasPermission" -> staffPermission;
                case "isOnline" -> online;
                case "sendMessage" -> {
                    messages++;
                    yield null;
                }
                case "sendPluginMessage" -> {
                    check(primaryThread, "bridge payload was sent off the main thread");
                    sent++;
                    yield null;
                }
                default -> null;
            });
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (object, method, args) -> {
                if (method.getName().equals("runTask")) tasks.add((Runnable) args[1]);
                return null;
            });
            ConsoleCommandSender console = proxy(ConsoleCommandSender.class, (object, method, args) -> {
                if (method.getName().equals("sendMessage")) messages++;
                return null;
            });
            Server server = proxy(Server.class, (object, method, args) -> switch (method.getName()) {
                case "isPrimaryThread" -> primaryThread;
                case "getScheduler" -> scheduler;
                case "getOnlinePlayers" -> List.of(player);
                case "getConsoleSender" -> console;
                default -> null;
            });
            var serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(null, server);
            Plugin plugin = proxy(Plugin.class, (object, method, args) ->
                    method.getName().equals("getServer") ? server : null);
            manager = new AfkManager(plugin);
            var trackerField = AfkManager.class.getDeclaredField("tracker");
            trackerField.setAccessible(true);
            tracker = (AfkTracker) trackerField.get(manager);
            bridge = new PaperChatBridge(null, activePlayer -> {
                check(primaryThread, "bridge activity was recorded off the main thread");
                check(activePlayer == player && sent == activities + 1,
                        "activity was recorded before successful plugin-message delivery");
                activities++;
                manager.recordActivity(activePlayer);
            });
        }

        void markAfk(boolean manual) {
            beforeSend = System.nanoTime();
            tracker.add(PLAYER, beforeSend - FIVE_MINUTES);
            if (manual) {
                tracker.toggle(PLAYER, beforeSend - FIVE_MINUTES);
            } else {
                check(tracker.markIdle(PLAYER, beforeSend), "fixture did not enter automatic AFK");
            }
        }

        PlayerCommandPreprocessEvent command(String message, boolean cancelled)
                throws ReflectiveOperationException {
            PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, message, Set.of());
            event.setCancelled(cancelled);
            dispatch(bridge, "onCommand", event);
            dispatch(manager, "onCommand", event);
            return event;
        }

        AsyncChatEvent staffChat(String message, boolean cancelled) throws ReflectiveOperationException {
            Component component = Component.text(message);
            AsyncChatEvent event = new AsyncChatEvent(!primaryThread, player, Set.of(),
                    (source, displayName, content, viewer) -> content, component, component, null);
            event.setCancelled(cancelled);
            dispatch(bridge, "onStaffPrefix", event);
            return event;
        }

        void checkReturned(String label) {
            check(!manager.isAfk(PLAYER), label + " did not clear AFK");
            check(messages == 0, label + " sent an AFK announcement");
            check(!tracker.markIdle(PLAYER, beforeSend + FIVE_MINUTES - 1L),
                    label + " did not restart the five-minute idle timeout");
        }

        void checkNoActivity() {
            check(sent == 0 && activities == 0 && tasks.isEmpty(),
                    "rejected or disconnected bridge communication recorded activity");
            check(manager.isAfk(PLAYER), "rejected bridge communication cleared AFK");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
