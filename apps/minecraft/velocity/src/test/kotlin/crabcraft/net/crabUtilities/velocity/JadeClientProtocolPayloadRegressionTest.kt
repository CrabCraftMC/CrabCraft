package crabcraft.net.crabUtilities.velocity

import java.util.Arrays

object JadeClientProtocolPayloadRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val encoded = ConnectionListener.encodeClientProtocol(776)
        check(Arrays.equals(encoded, byteArrayOf(0, 0, 3, 8)),
                "the proxy encoded the 26.2 protocol payload incorrectly")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
