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
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
