package crabcraft.net.crabUtilities.velocity.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class WebServerExecutorRegressionTest {

    public static void main(String[] args) throws Exception {
        openApiDocumentsTheChatStream();

        try (ThreadPoolExecutor executor = WebServer.createHttpExecutor(8)) {
            check(executor.getMaximumPoolSize() == 12,
                    "HTTP dispatch did not reserve bounded capacity for chat and ordinary requests");
            check(executor.getQueue().remainingCapacity() == 128,
                    "HTTP dispatch queue is not bounded");

            CountDownLatch started = new CountDownLatch(8);
            CountDownLatch release = new CountDownLatch(1);
            List<Future<?>> streams = new ArrayList<>();

            for (int i = 0; i < 8; i++) {
                streams.add(executor.submit(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            check(started.await(2, TimeUnit.SECONDS),
                    "long-lived requests did not start independently");
            Future<Boolean> ordinaryRequest = executor.submit(
                    () -> Thread.currentThread().isVirtual());
            check(Boolean.TRUE.equals(ordinaryRequest.get(1, TimeUnit.SECONDS)),
                    "long-lived requests starved an ordinary virtual-thread request");

            release.countDown();
            for (Future<?> stream : streams) {
                stream.get(1, TimeUnit.SECONDS);
            }
        }
    }

    private static void openApiDocumentsTheChatStream() throws Exception {
        Field field = WebServer.class.getDeclaredField("OPENAPI_JSON");
        field.setAccessible(true);
        JsonObject document = JsonParser.parseString((String) field.get(null)).getAsJsonObject();
        check(document.getAsJsonObject("paths").has("/chat/events"),
                "OpenAPI document omitted the public chat stream");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
