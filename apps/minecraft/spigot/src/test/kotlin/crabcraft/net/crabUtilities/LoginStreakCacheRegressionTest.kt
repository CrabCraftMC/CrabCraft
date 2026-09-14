package crabcraft.net.crabUtilities

object LoginStreakCacheRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val nineteen = snapshot(19, 100L)
        val twenty = snapshot(20, 200L)
        val refreshedTwenty = snapshot(20, 200L)

        check(LoginStreakCache.preferNewerSnapshot(twenty, nineteen) === twenty,
            "delayed join refresh replaced a newer pub/sub snapshot")
        check(LoginStreakCache.preferNewerSnapshot(nineteen, twenty) === twenty,
            "newer pub/sub snapshot was not accepted")
        check(LoginStreakCache.preferNewerSnapshot(twenty, refreshedTwenty) === refreshedTwenty,
            "equal-version refresh was not accepted")
    }

    private fun snapshot(streak: Int, lastLoginAt: Long): LoginStreakCache.StreakSnapshot =
        LoginStreakCache.StreakSnapshot(
            streak, streak, streak, lastLoginAt, lastLoginAt, lastLoginAt + 86_400L, true)

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
