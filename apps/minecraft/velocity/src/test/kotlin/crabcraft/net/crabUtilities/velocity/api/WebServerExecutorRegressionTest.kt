package crabcraft.net.crabUtilities.velocity.api

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object WebServerExecutorRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        WebServer.createHttpExecutor(8).use { executor ->
            check(
                executor.maximumPoolSize == 12,
                "HTTP dispatch did not reserve bounded capacity for chat and ordinary requests",
            )
            check(executor.queue.remainingCapacity() == 128, "HTTP dispatch queue is not bounded")
            val started = CountDownLatch(8)
            val release = CountDownLatch(1)
            val streams = ArrayList<Future<*>>()
            repeat(8) {
                streams.add(
                    executor.submit {
                        started.countDown()
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                )
            }
            check(started.await(2, TimeUnit.SECONDS), "long-lived requests did not start independently")
            val ordinaryRequest = executor.submit(Callable { Thread.currentThread().isVirtual })
            check(
                ordinaryRequest.get(1, TimeUnit.SECONDS),
                "long-lived requests starved an ordinary virtual-thread request",
            )
            release.countDown()
            for (stream in streams) stream.get(1, TimeUnit.SECONDS)
        }
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
