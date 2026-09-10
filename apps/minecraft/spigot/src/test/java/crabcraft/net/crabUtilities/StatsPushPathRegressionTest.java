package crabcraft.net.crabUtilities;

import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

final class StatsPushPathRegressionTest {

    public static void main(String[] args) {
        Path levelDirectory = Path.of("server", "world");
        Path dimensionDirectory = levelDirectory.resolve("dimensions/minecraft/overworld");
        Path playersDirectory = StatsPushTask.playerStorageDirectory(levelDirectory);

        check(playersDirectory.equals(levelDirectory.resolve("players")),
                "player storage was not resolved from the level directory");
        check(!playersDirectory.startsWith(dimensionDirectory),
                "player storage was incorrectly resolved inside the dimension directory");
        check(playersDirectory.resolve("stats").equals(levelDirectory.resolve("players/stats")),
                "stats directory does not match the Minecraft 26.1 layout");
        check(playersDirectory.resolve("advancements")
                        .equals(levelDirectory.resolve("players/advancements")),
                "advancements directory does not match the Minecraft 26.1 layout");
        check(playersDirectory.resolve("data")
                        .equals(levelDirectory.resolve(LevelResource.PLAYER_DATA_DIR.id())),
                "player data directory does not match the Minecraft 26.2 layout");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
