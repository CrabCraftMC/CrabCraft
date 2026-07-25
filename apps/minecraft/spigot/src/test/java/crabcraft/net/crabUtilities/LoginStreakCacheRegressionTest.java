package crabcraft.net.crabUtilities;

final class LoginStreakCacheRegressionTest {

    public static void main(String[] args) {
        LoginStreakCache.StreakSnapshot nineteen = snapshot(19, 100L);
        LoginStreakCache.StreakSnapshot twenty = snapshot(20, 200L);
        LoginStreakCache.StreakSnapshot refreshedTwenty = snapshot(20, 200L);

        check(LoginStreakCache.preferNewerSnapshot(twenty, nineteen) == twenty,
                "delayed join refresh replaced a newer pub/sub snapshot");
        check(LoginStreakCache.preferNewerSnapshot(nineteen, twenty) == twenty,
                "newer pub/sub snapshot was not accepted");
        check(LoginStreakCache.preferNewerSnapshot(twenty, refreshedTwenty) == refreshedTwenty,
                "equal-version refresh was not accepted");
    }

    private static LoginStreakCache.StreakSnapshot snapshot(int streak, long lastLoginAt) {
        return new LoginStreakCache.StreakSnapshot(
                streak, streak, streak, lastLoginAt, lastLoginAt, lastLoginAt + 86_400L, true);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
