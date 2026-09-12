package crabcraft.net.crabUtilities.appleskin

import java.nio.file.Files
import java.nio.file.Path

object AppleSkinLifecycleRegressionTest {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        shutdownInvalidatesSyncGenerations()
        syncTasksCancelAfterShutdown()
        integrationOwnsItsListenersAndChannels()
    }

    private fun shutdownInvalidatesSyncGenerations() {
        val before = AppleSkinIntegration.currentGeneration()
        AppleSkinIntegration.invalidateTasks()
        val after = AppleSkinIntegration.currentGeneration()
        check(after > before, "AppleSkin shutdown did not advance its task generation")
        check(!AppleSkinIntegration.isCurrentGeneration(after),
            "AppleSkin considered a task generation active after shutdown")
    }

    private fun syncTasksCancelAfterShutdown() {
        val bukkit = read("src/main/kotlin/crabcraft/net/crabUtilities/appleskin/AppleSkinSyncTask.kt")
        check(bukkit.contains("!AppleSkinIntegration.isCurrentGeneration(generation)") && bukkit.contains("cancel()"),
            "Bukkit AppleSkin tasks no longer cancel after a live disable")
    }

    private fun integrationOwnsItsListenersAndChannels() {
        val integration = read("src/main/kotlin/crabcraft/net/crabUtilities/appleskin/AppleSkinIntegration.kt")
        val disable = integration.indexOf("fun disable")
        val invalidateTasks = integration.indexOf("invalidateTasks()", disable)
        val unregisterListeners = integration.indexOf("HandlerList.unregisterAll", disable)
        val unregisterChannels = integration.indexOf("messenger.unregisterOutgoingPluginChannel", disable)
        check(disable >= 0 && invalidateTasks > disable && unregisterListeners > invalidateTasks
            && unregisterChannels > unregisterListeners,
            "AppleSkin no longer stops tasks before unregistering listeners and channels")
    }

    private fun read(path: String): String = Files.readString(Path.of(path))

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
