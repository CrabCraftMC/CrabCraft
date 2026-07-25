package crabcraft.net.crabUtilities.appleskin;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AppleSkinLifecycleRegressionTest {

    private AppleSkinLifecycleRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        shutdownInvalidatesSyncGenerations();
        syncTasksCancelAfterShutdown();
        integrationOwnsItsListenersAndChannels();
    }

    private static void shutdownInvalidatesSyncGenerations() {
        long before = AppleSkinIntegration.currentGeneration();
        AppleSkinIntegration.invalidateTasks();
        long after = AppleSkinIntegration.currentGeneration();

        check(after > before, "AppleSkin shutdown did not advance its task generation");
        check(!AppleSkinIntegration.isCurrentGeneration(after),
                "AppleSkin considered a task generation active after shutdown");
    }

    private static void syncTasksCancelAfterShutdown() throws Exception {
        String bukkit = read(
                "src/main/java/crabcraft/net/crabUtilities/appleskin/AppleSkinSyncTask.java");

        check(bukkit.contains("!AppleSkinIntegration.isCurrentGeneration(generation)")
                        && bukkit.contains("cancel();"),
                "Bukkit AppleSkin tasks no longer cancel after a live disable");
    }

    private static void integrationOwnsItsListenersAndChannels() throws Exception {
        String integration = read(
                "src/main/java/crabcraft/net/crabUtilities/appleskin/AppleSkinIntegration.java");

        int disable = integration.indexOf("public static synchronized void disable");
        int invalidateTasks = integration.indexOf("invalidateTasks();", disable);
        int unregisterListeners = integration.indexOf("HandlerList.unregisterAll", disable);
        int unregisterChannels = integration.indexOf(
                "messenger.unregisterOutgoingPluginChannel",
                disable);
        check(disable >= 0
                        && invalidateTasks > disable
                        && unregisterListeners > invalidateTasks
                        && unregisterChannels > unregisterListeners,
                "AppleSkin no longer stops tasks before unregistering listeners and channels");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
