package crabcraft.net.crabUtilities.redis

import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Supplier
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool

/** Runs the fixed-delay reconnect loop shared by Redis subscribers. */
object RedisSubscriberThread {
    /** The failure callback receives whether this is the first failure since recovery. */
    @JvmStatic
    fun start(
        name: String,
        pools: Supplier<JedisPool?>,
        subscribe: Consumer<Jedis>,
        reconnected: Runnable,
        failed: BiConsumer<Exception, Boolean>,
    ): Thread =
        Thread(
                {
                    var warned = false
                    while (!Thread.currentThread().isInterrupted) {
                        val pool = pools.get() ?: break
                        if (pool.isClosed) break
                        try {
                            pool.resource.use { jedis ->
                                if (warned) {
                                    reconnected.run()
                                    warned = false
                                }
                                subscribe.accept(jedis)
                            }
                        } catch (_: NoClassDefFoundError) {
                            break
                        } catch (e: Exception) {
                            if (Thread.currentThread().isInterrupted) break
                            failed.accept(e, !warned)
                            warned = true
                            try {
                                Thread.sleep(3000)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                    }
                },
                name,
            )
            .apply {
                isDaemon = true
                start()
            }
}
