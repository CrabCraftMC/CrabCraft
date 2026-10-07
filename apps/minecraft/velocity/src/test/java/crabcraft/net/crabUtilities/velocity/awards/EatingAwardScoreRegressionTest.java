package crabcraft.net.crabUtilities.velocity.awards;

import crabcraft.net.crabUtilities.awards.EatingAwardSnapshot;

import java.math.BigDecimal;

final class EatingAwardScoreRegressionTest {
    public static void main(String[] args) {
        var pending = new EatingAwardSnapshot(100, 200, 10, null);
        check(advance(2495L, null, pending) == 2495L,
                "initial checkpoint changed the existing score or counted ambiguous past meals");
        var next = new EatingAwardSnapshot(100, 300, 12, null);
        check(advance(2495L, pending, next) == 2497L,
                "pending history froze two new meals");
        check(advance(2497L, next, next) == null, "duplicate delivery counted meals twice");
        check(advance(2497L, next, pending) == null, "out-of-order delivery rolled back progress");
        check(advance(2497L, next, new EatingAwardSnapshot(100, 400, 10, null)) == null,
                "a stale offline save reset the meal checkpoint");

        var restored = EatingAwardSnapshot.fromJson(next.toJson());
        var afterRestart = new EatingAwardSnapshot(100, 400, 13, null);
        check(advance(2497L, restored, afterRestart) == 2498L,
                "a restart lost the durable checkpoint");
        var verified = new EatingAwardSnapshot(100, 500, 13, 100L);
        check(advance(2498L, afterRestart, verified) == 113L,
                "verified history did not replace the provisional score with all tracked meals");
        check(advance(113L, verified, new EatingAwardSnapshot(100, 600, 14, null)) == null,
                "missing history after a save rollback undid a verified correction");
        check(advance(113L, verified, new EatingAwardSnapshot(100, 600, 14, 100L)) == 114L,
                "meals after historical correction were counted incorrectly");
        check(advance(null, null, new EatingAwardSnapshot(100, 200, 2, 0L)) == 2L,
                "a new player did not receive their meals");
        check(advance(null, null, pending) == 10L,
                "a player without a previous leaderboard row lost confirmed meals");

        var restartedTracker = new EatingAwardSnapshot(700, 800, 1, null);
        check(advance(113L, verified, restartedTracker) == 113L,
                "a replacement player save erased the displayed score");
        check(advance(113L, restartedTracker, new EatingAwardSnapshot(700, 900, 2, null)) == 114L,
                "a replacement tracking epoch could not resume progress");
        check(advance(114L, restartedTracker, new EatingAwardSnapshot(100, 1000, 50, 100L)) == null,
                "an older tracking epoch replaced the current checkpoint");
        check(advance(9007199254740992L, pending, new EatingAwardSnapshot(100, 300, 11, null)) == 9007199254740993L,
                "a single meal was lost beyond double precision");
        check(advance(null, null, new EatingAwardSnapshot(100, 200, 1, Long.MAX_VALUE - 1)) == Long.MAX_VALUE,
                "a verified long total lost precision");
        var invalid = next.toJson();
        invalid.addProperty("meals", 1.5);
        try {
            EatingAwardSnapshot.fromJson(invalid);
            throw new AssertionError("fractional meals were silently truncated");
        } catch (ArithmeticException expected) {}
    }

    private static Long advance(Long score, EatingAwardSnapshot previous, EatingAwardSnapshot incoming) {
        BigDecimal result = EatingAwardScore.advance(score == null ? null : BigDecimal.valueOf(score), previous, incoming);
        return result == null ? null : result.longValueExact();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
