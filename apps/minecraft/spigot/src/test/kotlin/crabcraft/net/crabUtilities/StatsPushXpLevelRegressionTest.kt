package crabcraft.net.crabUtilities

import java.nio.file.Path
import java.util.Random

object StatsPushXpLevelRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val absentPlayerData = Path.of("absent-player.dat")

        check(StatsPushTask.resolveXpLevel(42, absentPlayerData).orElse(-1) == 42,
            "live Paper XP level was not used when player data was absent")
        check(StatsPushTask.resolveXpLevel(null, absentPlayerData).isEmpty,
            "offline player with no saved data returned an XP level")

        check(StatsPushTask.hasLiveXpLevelChanged(42, null),
            "first live XP snapshot would not trigger a push")
        check(StatsPushTask.hasLiveXpLevelChanged(43, 42),
            "changed live XP level would not trigger a push")
        check(!StatsPushTask.hasLiveXpLevelChanged(42, 42),
            "unchanged live XP level would trigger a push")
        check(!StatsPushTask.hasLiveXpLevelChanged(null, 42),
            "offline player was treated as a live XP change")

        val meals = (1 + Random(583_207L).nextInt(90)).toLong()
        val first = mapOf("eat_bread" to meals)
        val next = mapOf("eat_bread" to meals + 1)
        check(StatsPushTask.hasLiveEatingScoresChanged(emptyMap(), null),
            "a first snapshot must be pushed even while historical totals are pending")
        check(StatsPushTask.hasLiveEatingScoresChanged(next, first),
            "eating must trigger a push before vanilla stats are saved")
        check(!StatsPushTask.hasLiveEatingScoresChanged(first, first),
            "unchanged meals must not trigger repeated pushes")
        check(!StatsPushTask.hasLiveEatingScoresChanged(null, first),
            "an offline player was treated as a live meal change")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
