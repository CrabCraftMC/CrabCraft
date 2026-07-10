package crabcraft.net.crabUtilities.velocity;

import crabcraft.net.crabUtilities.velocity.db.LoginStreakService;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class LoginStreakCacheSeedRegressionTest {

    public static void main(String[] args) {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<LoginStreakService.StreakSnapshot> published = new AtomicReference<>();
        LoginStreakService.StreakSnapshot stored =
                new LoginStreakService.StreakSnapshot(19, 19, 123L, 123L);

        ConnectionListener.seedLoginStreakCache(() -> {
            loads.incrementAndGet();
            return stored;
        }, published::set);

        check(loads.get() == 1, "stored streak was not loaded exactly once");
        check(published.get() == stored, "stored 19-day streak was not published on login");

        AtomicInteger missingPublishes = new AtomicInteger();
        ConnectionListener.seedLoginStreakCache(() -> null, ignored -> missingPublishes.incrementAndGet());
        check(missingPublishes.get() == 0, "missing streak should not be published");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
