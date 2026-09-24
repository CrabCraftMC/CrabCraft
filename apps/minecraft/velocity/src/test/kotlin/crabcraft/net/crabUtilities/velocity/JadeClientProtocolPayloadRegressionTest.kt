package crabcraft.net.crabUtilities.velocity

object JadeClientProtocolPayloadRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val encoded = ConnectionListener.encodeClientProtocol(776)
        if (!encoded.contentEquals(byteArrayOf(0, 0, 3, 8))) {
            throw AssertionError("the proxy encoded the 26.2 protocol payload incorrectly")
        }
    }
}
