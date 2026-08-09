package crabcraft.net.crabUtilities.velocity.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class PublicChatBrokerRegressionTest {

    public static void main(String[] args) throws Exception {
        mapsRedisStreamEntriesToJsonAndSse();
        replaysRecentEventsAndResumesAfterAnId();
        fansOutLiveEventsAndKeepsTheNewestWhenAClientIsSlow();
        closesAndUnregistersSubscriptions();
    }

    private static void mapsRedisStreamEntriesToJsonAndSse() {
        StreamEntry entry = new StreamEntry(
                new StreamEntryID(1_725_000_000_123L, 4L),
                Map.of(
                        "uuid", "7e03f2d2-3292-4c69-9e80-40af3dd05065",
                        "username", "CrabPlayer",
                        "message", "hello\nworld"));

        PublicChatEvent event = PublicChatEvent.fromStreamEntry(entry);
        check("1725000000123-4".equals(event.id()), "stream ID was not preserved");
        check(event.timestamp() == 1_725_000_000_123L,
                "stream timestamp was not exposed as Unix epoch milliseconds");

        JsonObject json = JsonParser.parseString(event.toJson()).getAsJsonObject();
        check(json.size() == 4 && !json.has("id"),
                "JSON payload did not match the public chat contract");
        check(json.get("timestamp").getAsLong() == 1_725_000_000_123L,
                "JSON timestamp was not numeric");
        check("hello\nworld".equals(json.get("message").getAsString()),
                "JSON message changed");

        String frame = event.toSseFrame();
        check(frame.startsWith("id: 1725000000123-4\ndata: "),
                "SSE metadata was malformed");
        check(!frame.contains("event:"), "SSE frame would bypass EventSource.onmessage");
        check(frame.endsWith("\n\n"), "SSE frame lacked its terminating blank line");
        check(!frame.contains("hello\nworld"),
                "a message newline escaped the SSE data line");
    }

    private static void replaysRecentEventsAndResumesAfterAnId() throws Exception {
        PublicChatFeed feed = new PublicChatFeed(10, 10);
        for (int i = 1; i <= 8; i++) {
            feed.publish(event(i));
        }

        try (PublicChatSubscription initial = feed.subscribe(null, 6)) {
            for (int expected = 3; expected <= 8; expected++) {
                check(eventId(expected).equals(requireEvent(initial).id()),
                        "default replay was not the newest six events");
            }
        }

        try (PublicChatSubscription resumed = feed.subscribe(eventId(5), 6)) {
            for (int expected = 6; expected <= 8; expected++) {
                check(eventId(expected).equals(requireEvent(resumed).id()),
                        "Last-Event-ID replay did not resume after the supplied ID");
            }
            check(resumed.poll(1L, TimeUnit.MILLISECONDS) == null,
                    "resume replay included an event at or before Last-Event-ID");
        }

        feed.close();
    }

    private static void fansOutLiveEventsAndKeepsTheNewestWhenAClientIsSlow() throws Exception {
        PublicChatFeed feed = new PublicChatFeed(10, 2);
        try (PublicChatSubscription first = feed.subscribe(null, 0);
             PublicChatSubscription second = feed.subscribe(null, 0)) {
            feed.publish(event(1));
            check(eventId(1).equals(requireEvent(first).id()), "first subscriber missed fan-out");
            check(eventId(1).equals(requireEvent(second).id()), "second subscriber missed fan-out");

            feed.publish(event(2));
            feed.publish(event(3));
            feed.publish(event(4));
            check(eventId(3).equals(requireEvent(first).id()),
                    "slow subscriber queue did not discard its oldest event");
            check(eventId(4).equals(requireEvent(first).id()),
                    "slow subscriber queue did not retain the newest event");
        }
        feed.close();
    }

    private static void closesAndUnregistersSubscriptions() throws Exception {
        PublicChatFeed feed = new PublicChatFeed(10, 10);
        PublicChatSubscription subscription = feed.subscribe(null, 0);
        check(feed.subscriptionCount() == 1, "subscription was not registered");

        subscription.close();
        check(feed.subscriptionCount() == 0, "closed subscription remained registered");
        check(subscription.isClosed(), "subscription did not report closed state");

        PublicChatSubscription duringShutdown = feed.subscribe(null, 0);
        feed.close();
        check(duringShutdown.isClosed(), "feed shutdown did not close its subscription");
        check(duringShutdown.poll(1L, TimeUnit.MILLISECONDS) == null,
                "closed subscription returned an event");
    }

    private static PublicChatEvent requireEvent(PublicChatSubscription subscription)
            throws InterruptedException {
        PublicChatEvent event = subscription.poll(1L, TimeUnit.SECONDS);
        check(event != null, "timed out waiting for a chat event");
        return event;
    }

    private static PublicChatEvent event(int sequence) {
        return new PublicChatEvent(
                eventId(sequence),
                1_000L + sequence,
                "7e03f2d2-3292-4c69-9e80-40af3dd05065",
                "CrabPlayer",
                "message-" + sequence);
    }

    private static String eventId(int sequence) {
        return (1_000L + sequence) + "-0";
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
