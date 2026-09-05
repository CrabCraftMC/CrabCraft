package crabcraft.net.crabUtilities.velocity.voicechat;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import crabcraft.net.crabUtilities.velocity.VelocityConfig;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class PlayerLocationTrackerRegressionTest {

    public static void main(String[] args) throws Exception {
        committedHopPublishesWithoutHeartbeat();
        routeTokensAreHopSpecific();
        delayedHopUsesCurrentBackend();
        refreshCannotRegressNewRoute();
        oldDisconnectCannotDeleteNewSession();
    }

    private static void committedHopPublishesWithoutHeartbeat() throws Exception {
        var listener = PlayerLocationTracker.class.getMethod("onServerConnected", ServerPostConnectEvent.class);
        check(listener.isAnnotationPresent(Subscribe.class), "committed server event is not registered");
        UUID playerId = UUID.randomUUID();
        AtomicReference<String> backend = new AtomicReference<>("survival");
        ServerConnection connection = proxy(ServerConnection.class, (object, method, arguments) -> {
            if (method.getName().equals("getServerInfo")) {
                return new ServerInfo(backend.get(), new InetSocketAddress("127.0.0.1", 25565));
            }
            return null;
        });
        Player player = proxy(Player.class, (object, method, arguments) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "getCurrentServer" -> Optional.of(connection);
            default -> null;
        });
        ProxyServer server = proxy(ProxyServer.class, (object, method, arguments) ->
                method.getName().equals("getPlayer") ? Optional.of(player) : null);
        var logger = LoggerFactory.getLogger(PlayerLocationTrackerRegressionTest.class);
        var directory = Files.createTempDirectory("voice-location-regression");
        var plugin = new CrabUtilitiesVelocity(server, logger, directory);
        var tracker = new PlayerLocationTracker(plugin, VelocityConfig.load(directory, logger));
        AtomicReference<String> written = new AtomicReference<>();
        Jedis jedis = new Jedis() {
            @Override public String set(String key, String value, SetParams params) {
                written.set(value);
                return "OK";
            }
            @Override public void close() {}
        };
        try (JedisPool pool = new JedisPool() {
            @Override public Jedis getResource() { return jedis; }
        }) {
            var poolField = PlayerLocationTracker.class.getDeclaredField("jedisPool");
            poolField.setAccessible(true);
            poolField.set(tracker, pool);
            run(tracker.onServerConnected(new ServerPostConnectEvent(player, null)));
            String first = written.get();
            check(first != null && first.startsWith("survival\0"), "initial route waited for the refresh");

            backend.set("creative");
            EventTask delayed = tracker.onServerConnected(new ServerPostConnectEvent(player, null));
            backend.set("lobby");
            run(tracker.onServerConnected(new ServerPostConnectEvent(player, null)));
            String latest = written.get();
            run(delayed);
            check(latest.startsWith("lobby\0"), "server hop waited for the 30-second heartbeat");
            check(latest.equals(written.get()), "delayed event regressed the newer route");
            check(!first.equals(latest), "server hop reused the old route token");
        } finally {
            try (var files = Files.walk(directory)) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void run(EventTask task) {
        task.execute(new Continuation() {
            @Override public void resume() {}
            @Override public void resumeWithException(Throwable throwable) {
                throw new AssertionError("voice location event failed", throwable);
            }
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static void routeTokensAreHopSpecific() {
        String first = PlayerLocationTracker.routeValue("smp", "proxy", 1L);
        String second = PlayerLocationTracker.routeValue("smp", "proxy", 2L);
        check("smp\0proxy:1".equals(first), "route token encoding changed");
        check(!first.equals(second), "separate hops reused a route token");
    }

    private static void delayedHopUsesCurrentBackend() {
        Object lock = new Object();
        Session session = new Session("smp");
        AtomicReference<Session> current = new AtomicReference<>(session);
        AtomicReference<String> written = new AtomicReference<>();

        session.backend.set("creative");
        PlayerLocationTracker.updateCurrentSession(lock, session, current::get,
                live -> written.set(live.backend.get()));

        check("creative".equals(written.get()),
                "a delayed server-connected update wrote its captured backend");
    }

    private static void refreshCannotRegressNewRoute() {
        Object lock = new Object();
        Session session = new Session("smp");
        AtomicReference<Session> current = new AtomicReference<>(session);
        AtomicReference<String> written = new AtomicReference<>();
        CountDownLatch refreshRead = new CountDownLatch(1);
        CountDownLatch finishRefresh = new CountDownLatch(1);

        Thread refresh = start(() -> PlayerLocationTracker.updateCurrentSession(
                lock, session, current::get, live -> {
                    String backend = live.backend.get();
                    refreshRead.countDown();
                    await(finishRefresh);
                    written.set(backend);
                }));
        await(refreshRead);

        session.backend.set("creative");
        Thread serverConnected = start(() -> PlayerLocationTracker.updateCurrentSession(
                lock, session, current::get,
                live -> written.set(live.backend.get())));
        awaitBlocked(serverConnected);

        finishRefresh.countDown();
        join(refresh);
        join(serverConnected);
        check("creative".equals(written.get()),
                "an in-flight refresh regressed the newer server route");
    }

    private static void oldDisconnectCannotDeleteNewSession() {
        Object lock = new Object();
        Session oldSession = new Session("smp");
        AtomicReference<Session> current =
                new AtomicReference<>(new Session("creative"));
        AtomicBoolean deleted = new AtomicBoolean();

        PlayerLocationTracker.deleteDisconnectedSession(
                lock, oldSession, current::get, () -> deleted.set(true));

        check(!deleted.get(), "an old disconnect deleted a newer session");
    }

    private static Thread start(Runnable action) {
        Thread thread = new Thread(action);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(2, TimeUnit.SECONDS), "test coordination timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting test coordination", e);
        }
    }

    private static void awaitBlocked(Thread thread) {
        for (int attempts = 0; attempts < 200 && thread.getState() != Thread.State.BLOCKED;
             attempts++) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting serialised update", e);
            }
        }
        check(thread.getState() == Thread.State.BLOCKED,
                "same-player Redis operations were not serialised");
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining test thread", e);
        }
        check(!thread.isAlive(), "test thread did not finish");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Session {
        private final AtomicReference<String> backend;

        private Session(String backend) {
            this.backend = new AtomicReference<>(backend);
        }
    }
}
