package crabcraft.net.crabUtilities.velocity.voicechat

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.server.ServerInfo
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import crabcraft.net.crabUtilities.velocity.VelocityConfig
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object PlayerLocationTrackerRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        committedHopPublishesWithoutHeartbeat()
        routeTokensAreHopSpecific()
        delayedHopUsesCurrentBackend()
        refreshCannotRegressNewRoute()
        oldDisconnectCannotDeleteNewSession()
    }

    private fun committedHopPublishesWithoutHeartbeat() {
        val listener = PlayerLocationTracker::class.java.getMethod("onServerConnected", ServerPostConnectEvent::class.java)
        check(listener.isAnnotationPresent(Subscribe::class.java), "committed server event is not registered")
        val playerId = UUID.randomUUID()
        val backend = AtomicReference("survival")
        val connection = proxy(ServerConnection::class.java) { _, method, _ ->
            if (method.name == "getServerInfo") {
                ServerInfo(backend.get(), InetSocketAddress("127.0.0.1", 25565))
            } else null
        }
        val player = proxy(Player::class.java) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> playerId
                "getCurrentServer" -> Optional.of(connection)
                else -> null
            }
        }
        val server = proxy(ProxyServer::class.java) { _, method, _ ->
            if (method.name == "getPlayer") Optional.of(player) else null
        }
        val logger = LoggerFactory.getLogger(PlayerLocationTrackerRegressionTest::class.java)
        val directory = Files.createTempDirectory("voice-location-regression")
        val plugin = CrabUtilitiesVelocity(server, logger, directory)
        val tracker = PlayerLocationTracker(plugin, VelocityConfig.load(directory, logger))
        val written = AtomicReference<String>()
        val jedis = object : Jedis() {
            override fun set(key: String, value: String, params: SetParams): String {
                written.set(value)
                return "OK"
            }
            override fun close() {}
        }
        try {
            object : JedisPool() {
                override fun getResource(): Jedis = jedis
            }.use { pool ->
                val poolField = PlayerLocationTracker::class.java.getDeclaredField("jedisPool")
                poolField.isAccessible = true
                poolField.set(tracker, pool)
                run(tracker.onServerConnected(ServerPostConnectEvent(player, null)))
                val first = written.get()
                check(first != null && first.startsWith("survival\u0000"), "initial route waited for the refresh")

                backend.set("creative")
                val delayed = tracker.onServerConnected(ServerPostConnectEvent(player, null))
                backend.set("lobby")
                run(tracker.onServerConnected(ServerPostConnectEvent(player, null)))
                val latest = written.get()
                run(delayed)
                check(latest.startsWith("lobby\u0000"), "server hop waited for the 30-second heartbeat")
                check(latest == written.get(), "delayed event regressed the newer route")
                check(first != latest, "server hop reused the old route token")
            }
        } finally {
            Files.walk(directory).use { files ->
                for (path in files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path)
            }
        }
    }

    private fun run(task: EventTask) {
        task.execute(object : Continuation {
            override fun resume() {}
            override fun resumeWithException(throwable: Throwable) {
                throw AssertionError("voice location event failed", throwable)
            }
        })
    }

    private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))

    private fun routeTokensAreHopSpecific() {
        val first = PlayerLocationTracker.routeValue("smp", "proxy", 1L)
        val second = PlayerLocationTracker.routeValue("smp", "proxy", 2L)
        check("smp\u0000proxy:1" == first, "route token encoding changed")
        check(first != second, "separate hops reused a route token")
    }

    private fun delayedHopUsesCurrentBackend() {
        val lock = Any()
        val session = Session("smp")
        val current = AtomicReference(session)
        val written = AtomicReference<String>()
        session.backend.set("creative")
        PlayerLocationTracker.updateCurrentSession(lock, session, current::get) { live ->
            written.set(live.backend.get())
        }
        check("creative" == written.get(), "a delayed server-connected update wrote its captured backend")
    }

    private fun refreshCannotRegressNewRoute() {
        val lock = Any()
        val session = Session("smp")
        val current = AtomicReference(session)
        val written = AtomicReference<String>()
        val refreshRead = CountDownLatch(1)
        val finishRefresh = CountDownLatch(1)
        val refresh = start {
            PlayerLocationTracker.updateCurrentSession(lock, session, current::get) { live ->
                val backend = live.backend.get()
                refreshRead.countDown()
                await(finishRefresh)
                written.set(backend)
            }
        }
        await(refreshRead)
        session.backend.set("creative")
        val serverConnected = start {
            PlayerLocationTracker.updateCurrentSession(lock, session, current::get) { live ->
                written.set(live.backend.get())
            }
        }
        awaitBlocked(serverConnected)
        finishRefresh.countDown()
        join(refresh)
        join(serverConnected)
        check("creative" == written.get(), "an in-flight refresh regressed the newer server route")
    }

    private fun oldDisconnectCannotDeleteNewSession() {
        val lock = Any()
        val oldSession = Session("smp")
        val current = AtomicReference(Session("creative"))
        val deleted = AtomicBoolean()
        PlayerLocationTracker.deleteDisconnectedSession(lock, oldSession, current::get) { deleted.set(true) }
        check(!deleted.get(), "an old disconnect deleted a newer session")
    }

    private fun start(action: Runnable): Thread {
        val thread = Thread(action)
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun await(latch: CountDownLatch) {
        try {
            check(latch.await(2, TimeUnit.SECONDS), "test coordination timed out")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("interrupted while awaiting test coordination", e)
        }
    }

    private fun awaitBlocked(thread: Thread) {
        var attempts = 0
        while (attempts < 200 && thread.state != Thread.State.BLOCKED) {
            try {
                Thread.sleep(5L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("interrupted while awaiting serialised update", e)
            }
            attempts++
        }
        check(thread.state == Thread.State.BLOCKED, "same-player Redis operations were not serialised")
    }

    private fun join(thread: Thread) {
        try {
            thread.join(2_000L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("interrupted while joining test thread", e)
        }
        check(!thread.isAlive, "test thread did not finish")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private class Session(backend: String) {
        val backend = AtomicReference(backend)
    }
}
