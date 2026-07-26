package crabcraft.net.crabUtilities.velocity.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

public final class UpdateDownloaderRegressionTest {

    public static void main(String[] args) throws Exception {
        byte[] jar = "velocity-update".getBytes(StandardCharsets.UTF_8);
        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(jar));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/checksum", exchange -> respond(
                exchange,
                checksum + "  CrabUtilities-Velocity.jar\n",
                "application/octet-stream"));
        server.createContext("/jar", exchange -> respond(
                exchange,
                jar,
                "application/octet-stream"));
        server.start();

        var targetDir = Files.createTempDirectory("crabutilities-velocity-update-test");
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            var release = new ReleaseInfo(
                    "v1.1.0",
                    SemVer.parse("v1.1.0"),
                    "CrabUtilities-Velocity.jar",
                    baseUrl + "/jar",
                    baseUrl + "/checksum",
                    jar.length,
                    false);

            var downloaded = new UpdateDownloader("", "CrabUtilities regression test")
                    .download(release, targetDir, "CrabUtilities-Velocity.jar");
            check(Arrays.equals(jar, Files.readAllBytes(downloaded)),
                    "the verified Velocity update should be downloaded");
        } finally {
            server.stop(0);
            Files.deleteIfExists(targetDir.resolve("CrabUtilities-Velocity.jar"));
            Files.deleteIfExists(targetDir);
        }
    }

    private static void respond(HttpExchange exchange, String body, String expectedAccept)
            throws IOException {
        respond(exchange, body.getBytes(StandardCharsets.UTF_8), expectedAccept);
    }

    private static void respond(HttpExchange exchange, byte[] body, String expectedAccept)
            throws IOException {
        if (!expectedAccept.equals(exchange.getRequestHeaders().getFirst("Accept"))) {
            exchange.sendResponseHeaders(406, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
