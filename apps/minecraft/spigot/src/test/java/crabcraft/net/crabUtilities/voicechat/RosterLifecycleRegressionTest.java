package crabcraft.net.crabUtilities.voicechat;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Exercises Redis delivery separately from Bukkit task execution. */
final class RosterLifecycleRegressionTest {
    private static final UUID SPEAKER = UUID.randomUUID();
    private static final UUID GROUP = UUID.randomUUID();
    private static final String FIRST_ROUTE = "survival\0proxy:1";
    private static final String RETURN_ROUTE = "survival\0proxy:3";
    private static final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private static final Map<UUID, Player> localPlayers = new HashMap<>();
    private static final Player listener = player(UUID.randomUUID());

    public static void main(String[] args) throws ReflectiveOperationException {
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (method, arguments) -> {
            if (method.equals("runTask")) tasks.add((Runnable) arguments[1]);
            return null;
        });
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, proxy(Server.class, (method, arguments) -> switch (method) {
            case "getScheduler" -> scheduler;
            case "getPlayer" -> localPlayers.get(arguments[0]);
            case "getOnlinePlayers" -> List.of(listener);
            case "getLogger" -> Logger.getAnonymousLogger();
            case "getName", "getVersion", "getBukkitVersion" -> "voice-regression";
            default -> null;
        }));
        delayedLeaveCannotRemoveReturnToSameBackend();
        leaveStopsAudioAndRemovesState();
        catchUpCannotResurrectQueuedLeave();
        localArrivalDiscardsRemoteCache();
        groupMoveStopsOldAudio();
        shutdownCancelsQueuedRoster();
        legacyRosterStillDecodes();
    }

    private static void delayedLeaveCannotRemoveReturnToSameBackend() {
        Fixture fixture = new Fixture();
        fixture.join(GROUP, FIRST_ROUTE);
        fixture.join(GROUP, "creative\0proxy:2");
        fixture.join(GROUP, RETURN_ROUTE);
        fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE));
        drain();
        check(fixture.packets.states.containsKey(SPEAKER),
                "a delayed leave from the previous visit removed the return hop");
        check(fixture.packets.removals == 0, "a stale leave sent a client removal");
    }

    private static void leaveStopsAudioAndRemovesState() {
        Fixture fixture = new Fixture();
        fixture.join(GROUP, FIRST_ROUTE);
        fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE));
        drain();
        check(!fixture.packets.states.containsKey(SPEAKER), "departed speaker remained in the client roster");
        check(fixture.invalidated.equals(List.of(SPEAKER)), "leaving did not stop the remote audio channel");
    }

    private static void catchUpCannotResurrectQueuedLeave() {
        Fixture fixture = new Fixture();
        fixture.join(GROUP, FIRST_ROUTE);
        fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE));
        fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", RETURN_ROUTE));
        // Catch-up is another main-thread task; it must observe the same order
        // as normal client state updates, even when Redis runs ahead of Bukkit.
        tasks.add(() -> fixture.roster.catchUpNewLocalConnection(listener));
        drain();
        check(GROUP.equals(fixture.packets.states.get(SPEAKER)), "catch-up lost the latest hop");
        check(fixture.packets.events.getLast().equals("state"), "queued removal erased newer client state");
    }

    private static void localArrivalDiscardsRemoteCache() {
        Fixture fixture = new Fixture();
        fixture.join(GROUP, FIRST_ROUTE);
        localPlayers.put(SPEAKER, player(SPEAKER));
        fixture.roster.onLocalConnect(SPEAKER);
        fixture.join(GROUP, FIRST_ROUTE);
        fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE));
        drain();
        check(fixture.packets.removals == 0, "old remote leave removed native local state");
        localPlayers.remove(SPEAKER);
        fixture.packets.states.clear();
        fixture.roster.catchUpNewLocalConnection(listener);
        check(fixture.packets.states.isEmpty(), "catch-up resurrected the stale pre-arrival remote entry");
    }

    private static void groupMoveStopsOldAudio() {
        Fixture fixture = new Fixture();
        fixture.join(GROUP, FIRST_ROUTE);
        UUID nextGroup = UUID.randomUUID();
        fixture.join(nextGroup, FIRST_ROUTE);
        fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterLeave(GROUP, SPEAKER, FIRST_ROUTE));
        drain();
        check(nextGroup.equals(fixture.packets.states.get(SPEAKER)), "old-group leave removed the new group");
        check(fixture.invalidated.equals(List.of(SPEAKER)), "group move retained the old audio targets");
    }

    private static void legacyRosterStillDecodes() {
        VoiceMessages.RosterJoin legacy = VoiceMessages.decodeRosterJoin(
                VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", "survival"));
        check(legacy != null && "survival".equals(legacy.backend()), "legacy backend-only roster stopped decoding");
        VoiceMessages.RosterJoin current = VoiceMessages.decodeRosterJoin(
                VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", FIRST_ROUTE));
        check(current != null && FIRST_ROUTE.equals(current.route()), "roster discarded its hop token");
    }

    private static void shutdownCancelsQueuedRoster() {
        Fixture fixture = new Fixture();
        fixture.roster.onLifecycleMessage(VoiceMessages.encodeRosterJoin(GROUP, SPEAKER, "Crab", FIRST_ROUTE));
        fixture.roster.shutdown();
        drain();
        check(fixture.packets.states.isEmpty(), "queued roster join survived shutdown");
    }

    private static void drain() {
        while (!tasks.isEmpty()) tasks.remove().run();
    }

    private static Player player(UUID id) {
        return proxy(Player.class, (method, arguments) -> switch (method) {
            case "getUniqueId" -> id;
            case "getName" -> "Listener";
            case "isOnline" -> true;
            default -> null;
        });
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, arguments) -> handler.invoke(method.getName(), arguments)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Handler {
        Object invoke(String method, Object[] arguments);
    }

    private static final class Fixture {
        final Packets packets = new Packets();
        final List<UUID> invalidated = new ArrayList<>();
        final RosterTracker roster = new RosterTracker(null, packets, "lobby",
                Logger.getAnonymousLogger(), invalidated::add);

        void join(UUID group, String route) {
            roster.onLifecycleMessage(VoiceMessages.encodeRosterJoin(group, SPEAKER, "Crab", route));
            drain();
        }
    }

    private static final class Packets extends SvcPacketSender {
        final Map<UUID, UUID> states = new HashMap<>();
        final List<String> events = new ArrayList<>();
        int removals;

        Packets() { super(null); }

        @Override
        void sendState(Player recipient, UUID playerUuid, String name, UUID groupId) {
            states.put(playerUuid, groupId);
            events.add("state");
        }

        @Override
        void sendRemove(Player recipient, UUID playerUuid) {
            states.remove(playerUuid);
            events.add("remove");
            removals++;
        }
    }
}
