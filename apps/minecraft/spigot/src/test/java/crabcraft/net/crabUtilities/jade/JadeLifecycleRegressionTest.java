package crabcraft.net.crabUtilities.jade;

import java.nio.file.Files;
import java.nio.file.Path;

public final class JadeLifecycleRegressionTest {

    private JadeLifecycleRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        liveReloadOnlyRestoresValidatedPlayers();
    }

    private static void liveReloadOnlyRestoresValidatedPlayers() throws Exception {
        String bootstrap = Files.readString(Path.of(
                "src/main/java/crabcraft/net/crabUtilities/jade/JadeBootstrap.java"));

        int onlinePlayers = bootstrap.indexOf(
                "for (var player : plugin.getServer().getOnlinePlayers())");
        int validationGuard = bootstrap.indexOf(
                "if (!validatedPlayers.contains(serverPlayer))",
                onlinePlayers);
        int resend = bootstrap.indexOf(
                "JadeProtocol.resendHandshake",
                onlinePlayers);
        check(onlinePlayers >= 0
                        && validationGuard > onlinePlayers
                        && resend > validationGuard,
                "Jade live reload can resend a handshake to an unvalidated player");

        int disable = bootstrap.indexOf("public static synchronized void disable");
        int snapshot = bootstrap.indexOf(
                "validatedPlayers = JadeProtocol.snapshotEnabledPlayers();",
                disable);
        int shutdown = bootstrap.indexOf("JadeProtocol.shutdown();", disable);
        check(disable >= 0 && snapshot > disable && shutdown > snapshot,
                "Jade clears validated players before preserving live-reload state");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
