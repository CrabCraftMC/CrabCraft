package crabcraft.net.crabUtilities;

import java.nio.file.Path;

final class StatsPushXpLevelRegressionTest {

    public static void main(String[] args) {
        Path absentPlayerData = Path.of("absent-player.dat");

        check(StatsPushTask.resolveXpLevel(42, absentPlayerData).orElse(-1) == 42,
                "live Paper XP level was not used when player data was absent");
        check(StatsPushTask.resolveXpLevel(null, absentPlayerData).isEmpty(),
                "offline player with no saved data returned an XP level");

        check(StatsPushTask.hasLiveXpLevelChanged(42, null),
                "first live XP snapshot would not trigger a push");
        check(StatsPushTask.hasLiveXpLevelChanged(43, 42),
                "changed live XP level would not trigger a push");
        check(!StatsPushTask.hasLiveXpLevelChanged(42, 42),
                "unchanged live XP level would trigger a push");
        check(!StatsPushTask.hasLiveXpLevelChanged(null, 42),
                "offline player was treated as a live XP change");

        long meals = 1 + new java.util.Random(583_207L).nextInt(90);
        var first = java.util.Map.of("eat_bread", meals);
        var next = java.util.Map.of("eat_bread", meals + 1);
        check(StatsPushTask.hasLiveEatingScoresChanged(java.util.Map.of(), null),
                "a first snapshot must be pushed even while historical totals are pending");
        check(StatsPushTask.hasLiveEatingScoresChanged(next, first),
                "eating must trigger a push before vanilla stats are saved");
        check(!StatsPushTask.hasLiveEatingScoresChanged(first, first),
                "unchanged meals must not trigger repeated pushes");
        check(!StatsPushTask.hasLiveEatingScoresChanged(null, first),
                "an offline player was treated as a live meal change");

        var harvest = java.util.Map.of("harvest_honeycomb", 3L);
        check(StatsPushTask.resolveHarvestScores(harvest, absentPlayerData).equals(harvest),
                "live harvest progress must not wait for a player-data save");
        check(StatsPushTask.resolveHarvestScores(java.util.Map.of(), absentPlayerData).isEmpty(),
                "failed live harvest reads must not fall back to stale saved counters");
        check(StatsPushTask.resolveHarvestScores(null, absentPlayerData).isEmpty(),
                "missing offline counters must not fabricate zero scores");
        check(StatsPushTask.hasLiveHarvestScoresChanged(harvest, null)
                        && StatsPushTask.hasLiveHarvestScoresChanged(harvest, java.util.Map.of("harvest_honeycomb", 0L)),
                "new harvests must trigger a stats push before vanilla stats are saved");
        check(!StatsPushTask.hasLiveHarvestScoresChanged(harvest, harvest)
                        && !StatsPushTask.hasLiveHarvestScoresChanged(null, harvest),
                "unchanged or offline harvests must not trigger repeated pushes");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
