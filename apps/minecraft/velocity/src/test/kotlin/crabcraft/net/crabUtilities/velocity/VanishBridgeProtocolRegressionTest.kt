package crabcraft.net.crabUtilities.velocity

import crabcraft.net.crabUtilities.vanish.VanishBridgeProtocol

object VanishBridgeProtocolRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        check(!VanishBridgeProtocol.decode(VanishBridgeProtocol.status(false)), "visible state did not round-trip")
        check(VanishBridgeProtocol.decode(VanishBridgeProtocol.status(true)), "vanished state did not round-trip")
        rejects(byteArrayOf(1), "short payload was accepted")
        rejects(byteArrayOf(2, 0), "unknown protocol version was accepted")
        rejects(byteArrayOf(1, 2), "unknown status value was accepted")
        check(VanishManager.isPubliclyVisible("survival", false, "survival"), "current visible report was not public")
        check(!VanishManager.isPubliclyVisible("survival", true, "survival"), "vanished report was public")
        check(
            !VanishManager.isPubliclyVisible("survival", false, "creative"),
            "stale backend report remained public after a transfer",
        )
        check(!VanishManager.isPubliclyVisible(null, false, "survival"), "missing backend report was not fail-closed")
        check(
            ConnectionListener.isSilentJoinHost("Mods.CrabCraft.Net.", listOf("mods.crabcraft.net")),
            "silent join hostname was not normalised",
        )
        check(
            !ConnectionListener.isSilentJoinHost("crabcraft.net", listOf("mods.crabcraft.net")),
            "ordinary hostname was treated as silent",
        )
    }

    private fun rejects(payload: ByteArray, message: String) {
        try {
            VanishBridgeProtocol.decode(payload)
            throw AssertionError(message)
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
