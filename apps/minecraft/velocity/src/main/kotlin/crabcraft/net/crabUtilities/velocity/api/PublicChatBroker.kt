package crabcraft.net.crabUtilities.velocity.api

import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.Jedis
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.params.XReadParams
import redis.clients.jedis.resps.StreamEntry

/** Reads one Redis stream and fans events out, retaining the cursor across reconnects. */
class PublicChatBroker(
    host: String,
    private val port: Int,
    password: String?,
    stream: String,
    private val logger: Logger,
) : AutoCloseable {
    private val host = host.also {
        require(it.any { character -> !Character.isWhitespace(character) }) { "host must not be blank" }
    }
    private val password = password ?: ""
    private val stream = stream.also {
        require(it.any { character -> !Character.isWhitespace(character) }) { "stream must not be blank" }
    }
    private val feed = PublicChatFeed(DEFAULT_RECENT_CAPACITY, DEFAULT_SUBSCRIBER_CAPACITY)
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var readerThread: Thread? = null
    @Volatile private var activeJedis: Jedis? = null

    init {
        require(port in 1..65_535) { "port must be between 1 and 65535" }
    }

    @Synchronized
    fun start() {
        check(!closed) { "public chat broker is closed" }
        if (running) return
        running = true
        val thread = Thread(::readLoop, "CrabUtilities-PublicChat")
        thread.isDaemon = true
        readerThread = thread
        thread.start()
    }

    fun subscribe(lastEventId: String?, defaultReplay: Int): PublicChatSubscription =
        feed.subscribe(lastEventId, defaultReplay)

    private fun readLoop() {
        var cursor: StreamEntryID? = null
        var bootstrapped = false
        var warned = false
        try {
            while (running) {
                try {
                    openJedis().use { jedis ->
                        if (!running) return
                        activeJedis = jedis
                        if (!bootstrapped) {
                            val initial =
                                ArrayList(
                                    jedis.xrevrange(
                                        stream,
                                        StreamEntryID.MAXIMUM_ID,
                                        StreamEntryID.MINIMUM_ID,
                                        feed.recentCapacity(),
                                    )
                                )
                            initial.reverse()
                            cursor = StreamEntryID(0L, 0L)
                            for (entry in initial) cursor = handleEntry(entry)
                            bootstrapped = true
                        }
                        if (warned) {
                            logger.info("Public chat Redis reader reconnected")
                            warned = false
                        }
                        while (running) {
                            val result =
                                jedis.xread(
                                    XReadParams.xReadParams().count(READ_COUNT).block(READ_BLOCK_MS),
                                    mapOf(stream to cursor!!),
                                ) ?: continue
                            for (streamResult in result) {
                                for (entry in streamResult.value) cursor = handleEntry(entry)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!running) break
                    if (!warned) {
                        logger.warn("Public chat Redis reader unavailable; reconnecting in 3s: {}", e.message)
                        warned = true
                    } else {
                        logger.debug("Public chat Redis reader disconnected: {}", e.message)
                    }
                    if (!waitForReconnect()) break
                } finally {
                    activeJedis = null
                }
            }
        } finally {
            running = false
        }
    }

    private fun handleEntry(entry: StreamEntry): StreamEntryID {
        val id = entry.id
        try {
            feed.publish(PublicChatEvent.fromStreamEntry(entry))
        } catch (e: IllegalArgumentException) {
            logger.warn("Skipping malformed public chat event {}: {}", id, e.message)
        } catch (e: NullPointerException) {
            logger.warn("Skipping malformed public chat event {}: {}", id, e.message)
        }
        return id
    }

    private fun openJedis(): Jedis {
        val config =
            DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(CONNECT_TIMEOUT_MS)
                .socketTimeoutMillis(READ_TIMEOUT_MS)
                .blockingSocketTimeoutMillis(READ_TIMEOUT_MS)
                .clientName("crabutilities-public-chat")
        if (password.isNotEmpty()) config.password(password)
        return Jedis(host, port, config.build())
    }

    private fun waitForReconnect(): Boolean =
        try {
            TimeUnit.MILLISECONDS.sleep(RECONNECT_DELAY_MS)
            running
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    override fun close() {
        val thread: Thread?
        val jedis: Jedis?
        synchronized(this) {
            if (closed) return
            closed = true
            running = false
            thread = readerThread
            jedis = activeJedis
        }
        feed.close()
        if (jedis != null) {
            try {
                jedis.close()
            } catch (_: RuntimeException) {
                // The reader may have closed the same connection concurrently.
            }
        }
        if (thread != null) {
            thread.interrupt()
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(5_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        readerThread = null
        activeJedis = null
    }

    companion object {
        private const val DEFAULT_RECENT_CAPACITY = 100
        private const val DEFAULT_SUBSCRIBER_CAPACITY = 100
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_BLOCK_MS = 1_000
        private const val READ_TIMEOUT_MS = 3_000
        private const val READ_COUNT = 100
        private const val RECONNECT_DELAY_MS = 3_000L
    }
}
