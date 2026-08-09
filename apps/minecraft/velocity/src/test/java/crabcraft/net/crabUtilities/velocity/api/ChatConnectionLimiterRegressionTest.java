package crabcraft.net.crabUtilities.velocity.api;

final class ChatConnectionLimiterRegressionTest {

    public static void main(String[] args) {
        ChatConnectionLimiter limiter = new ChatConnectionLimiter(3, 2);

        check(limiter.tryAcquire("203.0.113.1"), "first connection was rejected");
        check(limiter.tryAcquire("203.0.113.1"), "second connection was rejected");
        check(!limiter.tryAcquire("203.0.113.1"), "per-IP limit was not enforced");
        check(limiter.tryAcquire("203.0.113.2"), "global final slot was rejected");
        check(!limiter.tryAcquire("203.0.113.3"), "global limit was not enforced");

        limiter.release("203.0.113.1");
        check(limiter.tryAcquire("203.0.113.3"), "released slot was not reusable");

        limiter.release("203.0.113.1");
        limiter.release("203.0.113.1");
        check(limiter.tryAcquire("203.0.113.1"), "duplicate release corrupted the limiter");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
