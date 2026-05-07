package crabcraft.net.crabUtilities.velocity.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class ClientIpResolver {

    private static final String CLOUDFLARE_V4_URL = "https://www.cloudflare.com/ips-v4";
    private static final String CLOUDFLARE_V6_URL = "https://www.cloudflare.com/ips-v6";

    private static final List<Cidr> LOOPBACK = List.of(
            Cidr.of("127.0.0.0/8"),
            Cidr.of("::1/128")
    );

    // Bundled last-known-good Cloudflare ranges. Used until the first
    // successful refresh from cloudflare.com so the rate limiter never
    // falls back to "trust nothing" if the fetch fails on startup.
    // Source: https://www.cloudflare.com/ips/
    private static final List<Cidr> CLOUDFLARE_FALLBACK = List.of(
            Cidr.of("173.245.48.0/20"),
            Cidr.of("103.21.244.0/22"),
            Cidr.of("103.22.200.0/22"),
            Cidr.of("103.31.4.0/22"),
            Cidr.of("141.101.64.0/18"),
            Cidr.of("108.162.192.0/18"),
            Cidr.of("190.93.240.0/20"),
            Cidr.of("188.114.96.0/20"),
            Cidr.of("197.234.240.0/22"),
            Cidr.of("198.41.128.0/17"),
            Cidr.of("162.158.0.0/15"),
            Cidr.of("104.16.0.0/13"),
            Cidr.of("104.24.0.0/14"),
            Cidr.of("172.64.0.0/13"),
            Cidr.of("131.0.72.0/22"),
            Cidr.of("2400:cb00::/32"),
            Cidr.of("2606:4700::/32"),
            Cidr.of("2803:f800::/32"),
            Cidr.of("2405:b500::/32"),
            Cidr.of("2405:8100::/32"),
            Cidr.of("2a06:98c0::/29"),
            Cidr.of("2c0f:f248::/32")
    );

    private static volatile List<Cidr> cloudflareRanges = CLOUDFLARE_FALLBACK;

    private ClientIpResolver() {}

    static String resolve(HttpExchange exchange) {
        String remote = exchange.getRemoteAddress().getHostString();
        if (!isTrustedProxy(remote)) {
            return remote;
        }
        Headers headers = exchange.getRequestHeaders();
        String cf = headers.getFirst("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        String xff = headers.getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma < 0 ? xff : xff.substring(0, comma)).trim();
        }
        return remote;
    }

    static void refreshCloudflareRanges(Logger logger) {
        try {
            List<Cidr> v4 = fetchCidrs(CLOUDFLARE_V4_URL);
            List<Cidr> v6 = fetchCidrs(CLOUDFLARE_V6_URL);
            if (v4.isEmpty() && v6.isEmpty()) {
                logger.warn("Cloudflare IP fetch returned no ranges; keeping previous list");
                return;
            }
            List<Cidr> merged = new ArrayList<>(v4.size() + v6.size());
            merged.addAll(v4);
            merged.addAll(v6);
            cloudflareRanges = List.copyOf(merged);
            logger.info("Refreshed Cloudflare IP ranges: {} v4, {} v6", v4.size(), v6.size());
        } catch (Exception e) {
            logger.warn("Failed to refresh Cloudflare IP ranges, keeping previous list: {}",
                    e.getMessage());
        }
    }

    private static List<Cidr> fetchCidrs(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "CrabUtilities-Velocity")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        }
        List<Cidr> out = new ArrayList<>();
        for (String line : resp.body().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                out.add(Cidr.of(trimmed));
            } catch (IllegalArgumentException ignored) {
                // skip non-CIDR lines defensively
            }
        }
        return out;
    }

    private static boolean isTrustedProxy(String ip) {
        InetAddress addr;
        try {
            addr = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return false;
        }
        for (Cidr cidr : LOOPBACK) {
            if (cidr.contains(addr)) return true;
        }
        for (Cidr cidr : cloudflareRanges) {
            if (cidr.contains(addr)) return true;
        }
        return false;
    }

    private static final class Cidr {
        private final byte[] prefix;
        private final int bits;

        private Cidr(byte[] prefix, int bits) {
            this.prefix = prefix;
            this.bits = bits;
        }

        static Cidr of(String spec) {
            int slash = spec.indexOf('/');
            String addr = slash < 0 ? spec : spec.substring(0, slash);
            try {
                byte[] bytes = InetAddress.getByName(addr).getAddress();
                int bits = slash < 0 ? bytes.length * 8 : Integer.parseInt(spec.substring(slash + 1));
                return new Cidr(bytes, bits);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR: " + spec, e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != prefix.length) return false;
            int fullBytes = bits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != prefix[i]) return false;
            }
            int rem = bits % 8;
            if (rem == 0) return true;
            int mask = (0xff << (8 - rem)) & 0xff;
            return (bytes[fullBytes] & mask) == (prefix[fullBytes] & mask);
        }
    }
}
