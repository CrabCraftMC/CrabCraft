package crabcraft.net.crabUtilities.velocity.voicechat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class PlayerLocationTrackerRegressionTest {

    public static void main(String[] args) {
        routeTokensAreHopSpecific();
        delayedHopUsesCurrentBackend();
        refreshCannotRegressNewRoute();
        oldDisconnectCannotDeleteNewSession();
    }

    private static void routeTokensAreHopSpecific() {
        String first = PlayerLocationTracker.routeValue("smp", "proxy", 1L);
        String second = PlayerLocationTracker.routeValue("smp", "proxy", 2L);
        check("smp\0proxy:1".equals(first), "route token encoding changed");
        check(!first.equals(second), "separate hops reused a route token");
    }

    private static void delayedHopUsesCurrentBackend() {
        Object lock = new Object();
        Session session = new Session("limbo");
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
        Session session = new Session("limbo");
        AtomicReference<Session> current = new AtomicReference<>(session);
        AtomicReference<String> written = new AtomicReference<>();
        CountDownLatch refreshRead = new CountDownLatch(1);
        CountDownLatch finishRefresh = new CountDownLatch(1);

        Worker refresh = start(() -> PlayerLocationTracker.updateCurrentSession(
                lock, session, current::get, live -> {
                    String backend = live.backend.get();
                    refreshRead.countDown();
                    await(finishRefresh);
                    written.set(backend);
                }));
        await(refreshRead);

        session.backend.set("creative");
        Worker serverConnected = start(() -> PlayerLocationTracker.updateCurrentSession(
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

    private static Worker start(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return new Worker(thread, failure);
    }

    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(2, TimeUnit.SECONDS), "test coordination timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting test coordination", e);
        }
    }

    private static void awaitBlocked(Worker worker) {
        Thread thread = worker.thread();
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

    private static void join(Worker worker) {
        Thread thread = worker.thread();
        try {
            thread.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining test thread", e);
        }
        check(!thread.isAlive(), "test thread did not finish");
        Throwable failure = worker.failure().get();
        if (failure != null) throw new AssertionError("test worker failed", failure);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Worker(Thread thread, AtomicReference<Throwable> failure) {}

    private static final class Session {
        private final AtomicReference<String> backend;

        private Session(String backend) {
            this.backend = new AtomicReference<>(backend);
        }
    }
}
