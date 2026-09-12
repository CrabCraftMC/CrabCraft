package crabcraft.net.crabUtilities.jade

object JadeClientProtocolPayloadRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        check(JadeBootstrap.decodeClientProtocol(byteArrayOf(0, 0, 3, 8)) == 776,
            "the proxy's 26.2 protocol payload was decoded incorrectly")
        check(JadeBootstrap.decodeClientProtocol(byteArrayOf(0, 0, 3)) == -1,
            "a truncated client protocol payload was accepted")
        check(JadeBootstrap.decodeClientProtocol(byteArrayOf(0, 0, 0, 0)) == -1,
            "an invalid client protocol was accepted")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
