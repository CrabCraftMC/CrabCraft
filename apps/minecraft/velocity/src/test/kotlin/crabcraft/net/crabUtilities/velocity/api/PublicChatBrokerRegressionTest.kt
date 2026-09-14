package crabcraft.net.crabUtilities.velocity.api

import com.google.gson.JsonParser
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.resps.StreamEntry
import java.util.concurrent.TimeUnit

object PublicChatBrokerRegressionTest {
    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        mapsRedisStreamEntriesToJsonAndSse()
        replaysRecentEventsAndResumesAfterAnId()
        fansOutLiveEventsAndKeepsTheNewestWhenAClientIsSlow()
        closesAndUnregistersSubscriptions()
    }

    private fun mapsRedisStreamEntriesToJsonAndSse() {
        val entry = StreamEntry(
            StreamEntryID(1_725_000_000_123L, 4L),
            mapOf(
                "uuid" to "7e03f2d2-3292-4c69-9e80-40af3dd05065",
                "username" to "CrabPlayer",
                "message" to "hello\nworld",
            ),
        )
        val event = PublicChatEvent.fromStreamEntry(entry)
        check(event.id() == "1725000000123-4", "stream ID was not preserved")
        check(event.timestamp() == 1_725_000_000_123L,
            "stream timestamp was not exposed as Unix epoch milliseconds")

        val json = JsonParser.parseString(event.toJson()).asJsonObject
        check(json.size() == 4 && !json.has("id"),
            "JSON payload did not match the public chat contract")
        check(json.get("timestamp").asLong == 1_725_000_000_123L,
            "JSON timestamp was not numeric")
        check(json.get("message").asString == "hello\nworld", "JSON message changed")

        val frame = event.toSseFrame()
        check(frame.startsWith("id: 1725000000123-4\ndata: "), "SSE metadata was malformed")
        check(!frame.contains("event:"), "SSE frame would bypass EventSource.onmessage")
        check(frame.endsWith("\n\n"), "SSE frame lacked its terminating blank line")
        check(!frame.contains("hello\nworld"), "a message newline escaped the SSE data line")
    }

    private fun replaysRecentEventsAndResumesAfterAnId() {
        val feed = PublicChatFeed(10, 10)
        for (i in 1..8) {
            feed.publish(event(i))
        }
        feed.subscribe(null, 6).use { initial ->
            for (expected in 3..8) {
                check(eventId(expected) == requireEvent(initial).id(),
                    "default replay was not the newest six events")
            }
        }
        feed.subscribe(eventId(5), 6).use { resumed ->
            for (expected in 6..8) {
                check(eventId(expected) == requireEvent(resumed).id(),
                    "Last-Event-ID replay did not resume after the supplied ID")
            }
            check(resumed.poll(1L, TimeUnit.MILLISECONDS) == null,
                "resume replay included an event at or before Last-Event-ID")
        }
        feed.close()
    }

    private fun fansOutLiveEventsAndKeepsTheNewestWhenAClientIsSlow() {
        val feed = PublicChatFeed(10, 2)
        feed.subscribe(null, 0).use { first ->
            feed.subscribe(null, 0).use { second ->
                feed.publish(event(1))
                check(eventId(1) == requireEvent(first).id(), "first subscriber missed fan-out")
                check(eventId(1) == requireEvent(second).id(), "second subscriber missed fan-out")
                feed.publish(event(2))
                feed.publish(event(3))
                feed.publish(event(4))
                check(eventId(3) == requireEvent(first).id(),
                    "slow subscriber queue did not discard its oldest event")
                check(eventId(4) == requireEvent(first).id(),
                    "slow subscriber queue did not retain the newest event")
            }
        }
        feed.close()
    }

    private fun closesAndUnregistersSubscriptions() {
        val feed = PublicChatFeed(10, 10)
        val subscription = feed.subscribe(null, 0)
        check(feed.subscriptionCount() == 1, "subscription was not registered")
        subscription.close()
        check(feed.subscriptionCount() == 0, "closed subscription remained registered")
        check(subscription.isClosed(), "subscription did not report closed state")
        val duringShutdown = feed.subscribe(null, 0)
        feed.close()
        check(duringShutdown.isClosed(), "feed shutdown did not close its subscription")
        check(duringShutdown.poll(1L, TimeUnit.MILLISECONDS) == null,
            "closed subscription returned an event")
    }

    private fun requireEvent(subscription: PublicChatSubscription): PublicChatEvent {
        val event = subscription.poll(1L, TimeUnit.SECONDS)
        check(event != null, "timed out waiting for a chat event")
        return event!!
    }

    private fun event(sequence: Int): PublicChatEvent = PublicChatEvent(
        eventId(sequence),
        1_000L + sequence,
        "7e03f2d2-3292-4c69-9e80-40af3dd05065",
        "CrabPlayer",
        "message-" + sequence,
    )

    private fun eventId(sequence: Int): String = (1_000L + sequence).toString() + "-0"

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
