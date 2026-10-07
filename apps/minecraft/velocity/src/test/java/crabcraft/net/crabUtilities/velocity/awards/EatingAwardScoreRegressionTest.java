package crabcraft.net.crabUtilities.velocity.awards;

import crabcraft.net.crabUtilities.awards.EatingAwardSnapshot;

final class EatingAwardScoreRegressionTest {
    public static void main(String[] args) {
        var pending = new EatingAwardSnapshot(100, 200, 10, null);
        check(EatingAwardScore.advance(2495d, null, pending) == 2495d,
                "initial checkpoint changed the existing score or counted ambiguous past meals");
        var next = new EatingAwardSnapshot(100, 300, 12, null);
        check(EatingAwardScore.advance(2495d, pending, next) == 2497d,
                "pending history froze two new meals");
        check(EatingAwardScore.advance(2497d, next, next) == null, "duplicate delivery counted meals twice");
        check(EatingAwardScore.advance(2497d, next, pending) == null, "out-of-order delivery rolled back progress");
        check(EatingAwardScore.advance(2497d, next, new EatingAwardSnapshot(100, 400, 10, null)) == null,
                "a stale offline save reset the meal checkpoint");

        var restored = EatingAwardSnapshot.fromJson(next.toJson());
        var afterRestart = new EatingAwardSnapshot(100, 400, 13, null);
        check(EatingAwardScore.advance(2497d, restored, afterRestart) == 2498d,
                "a restart lost the durable checkpoint");
        var verified = new EatingAwardSnapshot(100, 500, 13, 100L);
        check(EatingAwardScore.advance(2498d, afterRestart, verified) == 113d,
                "verified history did not replace the provisional score with all tracked meals");
        check(EatingAwardScore.advance(113d, verified, new EatingAwardSnapshot(100, 600, 14, null)) == null,
                "missing history after a save rollback undid a verified correction");
        check(EatingAwardScore.advance(113d, verified, new EatingAwardSnapshot(100, 600, 14, 100L)) == 114d,
                "meals after historical correction were counted incorrectly");
        check(EatingAwardScore.advance(null, null, new EatingAwardSnapshot(100, 200, 2, 0L)) == 2d,
                "a new player did not receive their meals");
        check(EatingAwardScore.advance(null, null, pending) == 10d,
                "a player without a previous leaderboard row lost confirmed meals");

        var restartedTracker = new EatingAwardSnapshot(700, 800, 1, null);
        check(EatingAwardScore.advance(113d, verified, restartedTracker) == 113d,
                "a replacement player save erased the displayed score");
        check(EatingAwardScore.advance(113d, restartedTracker, new EatingAwardSnapshot(700, 900, 2, null)) == 114d,
                "a replacement tracking epoch could not resume progress");
        check(EatingAwardScore.advance(114d, restartedTracker, new EatingAwardSnapshot(100, 1000, 50, 100L)) == null,
                "an older tracking epoch replaced the current checkpoint");
        var invalid = next.toJson();
        invalid.addProperty("meals", 1.5);
        try {
            EatingAwardSnapshot.fromJson(invalid);
            throw new AssertionError("fractional meals were silently truncated");
        } catch (ArithmeticException expected) {}
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
