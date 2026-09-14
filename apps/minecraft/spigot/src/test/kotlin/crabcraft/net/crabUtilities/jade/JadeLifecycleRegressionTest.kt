package crabcraft.net.crabUtilities.jade

import java.nio.file.Files
import java.nio.file.Path

object JadeLifecycleRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        liveReloadOnlyRestoresValidatedPlayers()
    }

    private fun liveReloadOnlyRestoresValidatedPlayers() {
        val bootstrap = Files.readString(Path.of(
            "src/main/kotlin/crabcraft/net/crabUtilities/jade/JadeBootstrap.kt"))

        val onlinePlayers = bootstrap.indexOf(
            "for (player in plugin.server.onlinePlayers)")
        val validationGuard = bootstrap.indexOf(
            "if (!validatedPlayers.contains(serverPlayer))",
            onlinePlayers)
        val resend = bootstrap.indexOf(
            "JadeProtocol.resendHandshake",
            onlinePlayers)
        check(onlinePlayers >= 0
                && validationGuard > onlinePlayers
                && resend > validationGuard,
            "Jade live reload can resend a handshake to an unvalidated player")

        val disable = bootstrap.indexOf("fun disable(")
        val snapshot = bootstrap.indexOf(
            "validatedPlayers = JadeProtocol.snapshotEnabledPlayers()",
            disable)
        val shutdown = bootstrap.indexOf("JadeProtocol.shutdown()", disable)
        check(disable >= 0 && snapshot > disable && shutdown > snapshot,
            "Jade clears validated players before preserving live-reload state")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
