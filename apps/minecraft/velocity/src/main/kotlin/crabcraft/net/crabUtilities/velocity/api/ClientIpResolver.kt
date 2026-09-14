package crabcraft.net.crabUtilities.velocity.api

import com.sun.net.httpserver.HttpExchange
import org.slf4j.Logger
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ClientIpResolver private constructor() {
    companion object {
        private const val CLOUDFLARE_V4_URL = "https://www.cloudflare.com/ips-v4"
        private const val CLOUDFLARE_V6_URL = "https://www.cloudflare.com/ips-v6"
        private val LOOPBACK = listOf(Cidr.of("127.0.0.0/8"), Cidr.of("::1/128"))

        // Bundled last-known-good Cloudflare ranges, used until a successful refresh.
        // Source: https://www.cloudflare.com/ips/
        private val CLOUDFLARE_FALLBACK = listOf(
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
        )
        @Volatile private var cloudflareRanges = CLOUDFLARE_FALLBACK

        @JvmStatic fun resolve(exchange: HttpExchange): String {
            val remote = exchange.remoteAddress.hostString
            if (!isTrustedProxy(remote)) return remote
            val headers = exchange.requestHeaders
            val cf = headers.getFirst("CF-Connecting-IP")
            if (cf != null && !cf.codePoints().allMatch(Character::isWhitespace)) return cf.trim { it <= ' ' }
            val xff = headers.getFirst("X-Forwarded-For")
            if (xff != null && !xff.codePoints().allMatch(Character::isWhitespace)) {
                val comma = xff.indexOf(',')
                return (if (comma < 0) xff else xff.substring(0, comma)).trim { it <= ' ' }
            }
            return remote
        }

        @JvmStatic fun refreshCloudflareRanges(logger: Logger) {
            try {
                val v4 = fetchCidrs(CLOUDFLARE_V4_URL)
                val v6 = fetchCidrs(CLOUDFLARE_V6_URL)
                if (v4.isEmpty() && v6.isEmpty()) {
                    logger.warn("Cloudflare IP fetch returned no ranges; keeping previous list")
                    return
                }
                val merged = ArrayList<Cidr>(v4.size + v6.size)
                merged.addAll(v4)
                merged.addAll(v6)
                cloudflareRanges = java.util.List.copyOf(merged)
                logger.info("Refreshed Cloudflare IP ranges: {} v4, {} v6", v4.size, v6.size)
            } catch (e: Exception) {
                logger.warn("Failed to refresh Cloudflare IP ranges, keeping previous list: {}", e.message)
            }
        }

        private fun fetchCidrs(url: String): List<Cidr> {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "CrabUtilities-Velocity")
                .GET().build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) throw IOException("HTTP " + resp.statusCode() + " from " + url)
            val out = ArrayList<Cidr>()
            for (line in resp.body().split(Regex("\\R"))) {
                val trimmed = line.trim { it <= ' ' }
                if (trimmed.isEmpty()) continue
                try {
                    out.add(Cidr.of(trimmed))
                } catch (ignored: IllegalArgumentException) {
                    // skip non-CIDR lines defensively
                }
            }
            return out
        }

        private fun isTrustedProxy(ip: String): Boolean {
            val addr = try { InetAddress.getByName(ip) } catch (e: UnknownHostException) { return false }
            for (cidr in LOOPBACK) if (cidr.contains(addr)) return true
            for (cidr in cloudflareRanges) if (cidr.contains(addr)) return true
            return false
        }
    }

    private class Cidr(private val prefix: ByteArray, private val bits: Int) {
        fun contains(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size != prefix.size) return false
            val fullBytes = bits / 8
            for (i in 0 until fullBytes) if (bytes[i] != prefix[i]) return false
            val rem = bits % 8
            if (rem == 0) return true
            val mask = (0xff shl (8 - rem)) and 0xff
            return (bytes[fullBytes].toInt() and mask) == (prefix[fullBytes].toInt() and mask)
        }

        companion object {
            fun of(spec: String): Cidr {
                val slash = spec.indexOf('/')
                val addr = if (slash < 0) spec else spec.substring(0, slash)
                try {
                    val bytes = InetAddress.getByName(addr).address
                    val bits = if (slash < 0) bytes.size * 8 else spec.substring(slash + 1).toInt()
                    return Cidr(bytes, bits)
                } catch (e: UnknownHostException) {
                    throw IllegalArgumentException("Invalid CIDR: " + spec, e)
                } catch (e: NumberFormatException) {
                    throw IllegalArgumentException("Invalid CIDR: " + spec, e)
                }
            }
        }
    }
}
