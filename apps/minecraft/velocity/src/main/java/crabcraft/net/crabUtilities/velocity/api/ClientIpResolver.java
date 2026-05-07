package crabcraft.net.crabUtilities.velocity.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

final class ClientIpResolver {

    // Cloudflare's published edge ranges plus loopback. Refresh from
    // https://www.cloudflare.com/ips/ if Cloudflare ever publishes new ranges.
    private static final List<Cidr> TRUSTED_PROXIES = List.of(
            Cidr.of("127.0.0.0/8"),
            Cidr.of("::1/128"),
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

    private static boolean isTrustedProxy(String ip) {
        InetAddress addr;
        try {
            addr = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return false;
        }
        for (Cidr cidr : TRUSTED_PROXIES) {
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
