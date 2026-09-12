package crabcraft.net.crabUtilities.jade.protocol

object JadeMessengerWarningLimiterRegressionTest {

    @JvmStatic
    fun main(args: Array<String>) {
        val limiter = JadeMessenger.WarningLimiter(100)

        check(limiter.claim(0) == 0, "first malformed payload should be logged")
        check(limiter.claim(1) == -1, "payload inside the interval was not suppressed")
        check(limiter.claim(99) == -1, "repeated payload inside the interval was not suppressed")
        check(limiter.claim(100) == 2, "suppressed count was not reported at the next interval")
        check(limiter.claim(101) == -1, "new interval did not begin throttling again")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
