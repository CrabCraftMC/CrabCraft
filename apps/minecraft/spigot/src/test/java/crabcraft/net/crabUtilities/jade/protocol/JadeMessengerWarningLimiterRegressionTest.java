package crabcraft.net.crabUtilities.jade.protocol;

public final class JadeMessengerWarningLimiterRegressionTest {

    private JadeMessengerWarningLimiterRegressionTest() {
    }

    public static void main(String[] args) {
        JadeMessenger.WarningLimiter limiter = new JadeMessenger.WarningLimiter(100);

        check(limiter.claim(0) == 0, "first malformed payload should be logged");
        check(limiter.claim(1) == -1, "payload inside the interval was not suppressed");
        check(limiter.claim(99) == -1, "repeated payload inside the interval was not suppressed");
        check(limiter.claim(100) == 2, "suppressed count was not reported at the next interval");
        check(limiter.claim(101) == -1, "new interval did not begin throttling again");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
