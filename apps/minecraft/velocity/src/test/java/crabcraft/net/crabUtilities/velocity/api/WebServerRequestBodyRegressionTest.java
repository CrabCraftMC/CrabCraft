package crabcraft.net.crabUtilities.velocity.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WebServerRequestBodyRegressionTest {
    private WebServerRequestBodyRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        byte[] allowed = "a".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);
        String decoded = WebServer.readRequestBody(new ByteArrayInputStream(allowed));
        check(decoded.length() == allowed.length, "64 KiB request should be accepted");

        byte[] oversized = "a".repeat(64 * 1024 + 1).getBytes(StandardCharsets.UTF_8);
        boolean rejected = false;
        try {
            WebServer.readRequestBody(new ByteArrayInputStream(oversized));
        } catch (IOException expected) {
            rejected = true;
        }
        check(rejected, "request larger than 64 KiB should be rejected");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
