package crabcraft.net.crabUtilities.velocity.api

import com.google.gson.JsonParser
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object WebServerExecutorRegressionTest {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        openApiDocumentsTheChatStream()

        WebServer.createHttpExecutor(8).use { executor ->
            check(executor.maximumPoolSize == 12,
                "HTTP dispatch did not reserve bounded capacity for chat and ordinary requests")
            check(executor.queue.remainingCapacity() == 128,
                "HTTP dispatch queue is not bounded")

            val started = CountDownLatch(8)
            val release = CountDownLatch(1)
            val streams = ArrayList<Future<*>>()

            for (i in 0 until 8) {
                streams.add(executor.submit {
                    started.countDown()
                    try {
                        release.await()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                })
            }

            check(started.await(2, TimeUnit.SECONDS),
                "long-lived requests did not start independently")
            val ordinaryRequest = executor.submit(Callable { Thread.currentThread().isVirtual })
            check(ordinaryRequest.get(1, TimeUnit.SECONDS) == true,
                "long-lived requests starved an ordinary virtual-thread request")

            release.countDown()
            for (stream in streams) {
                stream.get(1, TimeUnit.SECONDS)
            }
        }
    }

    private fun openApiDocumentsTheChatStream() {
        val field = WebServer::class.java.getDeclaredField("OPENAPI_JSON")
        field.isAccessible = true
        val document = JsonParser.parseString(field.get(null) as String).asJsonObject
        check(document.getAsJsonObject("paths").has("/chat/events"),
            "OpenAPI document omitted the public chat stream")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
