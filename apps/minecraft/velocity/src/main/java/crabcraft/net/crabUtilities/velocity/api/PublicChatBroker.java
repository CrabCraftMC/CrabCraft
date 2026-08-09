package crabcraft.net.crabUtilities.velocity.api;

import org.slf4j.Logger;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Reads a Redis Stream once and fans public chat events out to local SSE
 * subscribers. Redis reconnects resume from the most recently handled stream
 * ID, while new broker instances seed their bounded replay buffer from Redis.
 */
final class PublicChatBroker implements AutoCloseable {

    private static final int DEFAULT_RECENT_CAPACITY = 100;
    private static final int DEFAULT_SUBSCRIBER_CAPACITY = 100;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_BLOCK_MS = 1_000;
    private static final int READ_TIMEOUT_MS = 3_000;
    private static final int READ_COUNT = 100;
    private static final long RECONNECT_DELAY_MS = 3_000L;

    private final String host;
    private final int port;
    private final String password;
    private final String stream;
    private final Logger logger;
    private final PublicChatFeed feed;

    private volatile boolean running;
    private volatile boolean closed;
    private volatile Thread readerThread;
    private volatile Jedis activeJedis;

    PublicChatBroker(String host, int port, String password, String stream,
                     Logger logger) {
        this.host = requireText(host, "host");
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.port = port;
        this.password = password == null ? "" : password;
        this.stream = requireText(stream, "stream");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.feed = new PublicChatFeed(DEFAULT_RECENT_CAPACITY, DEFAULT_SUBSCRIBER_CAPACITY);
    }

    synchronized void start() {
        if (closed) throw new IllegalStateException("public chat broker is closed");
        if (running) return;

        running = true;
        Thread thread = new Thread(this::readLoop, "CrabUtilities-PublicChat");
        thread.setDaemon(true);
        readerThread = thread;
        thread.start();
    }

    PublicChatSubscription subscribe(String lastEventId, int defaultReplay) {
        return feed.subscribe(lastEventId, defaultReplay);
    }

    private void readLoop() {
        StreamEntryID cursor = null;
        boolean bootstrapped = false;
        boolean warned = false;
        try {
            while (running) {
                try (Jedis jedis = openJedis()) {
                    if (!running) break;
                    activeJedis = jedis;

                    if (!bootstrapped) {
                        List<StreamEntry> initial = new ArrayList<>(jedis.xrevrange(
                                stream,
                                StreamEntryID.MAXIMUM_ID,
                                StreamEntryID.MINIMUM_ID,
                                feed.recentCapacity()));
                        Collections.reverse(initial);
                        cursor = new StreamEntryID(0L, 0L);
                        for (StreamEntry entry : initial) {
                            cursor = handleEntry(entry);
                        }
                        bootstrapped = true;
                    }

                    if (warned) {
                        logger.info("Public chat Redis reader reconnected");
                        warned = false;
                    }

                    while (running) {
                        List<Map.Entry<String, List<StreamEntry>>> result = jedis.xread(
                                XReadParams.xReadParams().count(READ_COUNT).block(READ_BLOCK_MS),
                                Map.of(stream, cursor));
                        if (result == null) continue;
                        for (Map.Entry<String, List<StreamEntry>> streamResult : result) {
                            for (StreamEntry entry : streamResult.getValue()) {
                                cursor = handleEntry(entry);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!running) break;
                    if (!warned) {
                        logger.warn("Public chat Redis reader unavailable; reconnecting in 3s: {}",
                                e.getMessage());
                        warned = true;
                    } else {
                        logger.debug("Public chat Redis reader disconnected: {}", e.getMessage());
                    }
                    if (!waitForReconnect()) break;
                } finally {
                    activeJedis = null;
                }
            }
        } finally {
            running = false;
        }
    }

    private StreamEntryID handleEntry(StreamEntry entry) {
        StreamEntryID id = entry.getID();
        try {
            feed.publish(PublicChatEvent.fromStreamEntry(entry));
        } catch (IllegalArgumentException | NullPointerException e) {
            logger.warn("Skipping malformed public chat event {}: {}", id, e.getMessage());
        }
        return id;
    }

    private Jedis openJedis() {
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(CONNECT_TIMEOUT_MS)
                .socketTimeoutMillis(READ_TIMEOUT_MS)
                .blockingSocketTimeoutMillis(READ_TIMEOUT_MS)
                .clientName("crabutilities-public-chat");
        if (!password.isEmpty()) {
            config.password(password);
        }
        return new Jedis(host, port, config.build());
    }

    private boolean waitForReconnect() {
        try {
            TimeUnit.MILLISECONDS.sleep(RECONNECT_DELAY_MS);
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        Thread thread;
        Jedis jedis;
        synchronized (this) {
            if (closed) return;
            closed = true;
            running = false;
            thread = readerThread;
            jedis = activeJedis;
        }

        feed.close();
        if (jedis != null) {
            try {
                jedis.close();
            } catch (RuntimeException ignored) {
                // The reader may have closed the same connection concurrently.
            }
        }
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        readerThread = null;
        activeJedis = null;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
