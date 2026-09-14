package crabcraft.net.crabUtilities

import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path

object StatsPushPathRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val levelDirectory = Path.of("server", "world")
        val dimensionDirectory = levelDirectory.resolve("dimensions/minecraft/overworld")
        val playersDirectory = StatsPushTask.playerStorageDirectory(levelDirectory)

        check(playersDirectory == levelDirectory.resolve("players"),
            "player storage was not resolved from the level directory")
        check(!playersDirectory.startsWith(dimensionDirectory),
            "player storage was incorrectly resolved inside the dimension directory")
        check(playersDirectory.resolve("stats") == levelDirectory.resolve("players/stats"),
            "stats directory does not match the Minecraft 26.1 layout")
        check(playersDirectory.resolve("advancements") == levelDirectory.resolve("players/advancements"),
            "advancements directory does not match the Minecraft 26.1 layout")
        check(playersDirectory.resolve("data") == levelDirectory.resolve(LevelResource.PLAYER_DATA_DIR.id()),
            "player data directory does not match the Minecraft 26.2 layout")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
