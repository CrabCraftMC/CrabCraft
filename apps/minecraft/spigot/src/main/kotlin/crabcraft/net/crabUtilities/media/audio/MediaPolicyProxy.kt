package crabcraft.net.crabUtilities.media.audio

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors

/** Revalidates every proxy request, including redirects and nested manifest resources. */
class MediaPolicyProxy @Throws(IOException::class) constructor(policy: MediaDestinationPolicy, upstreamProxy: String?) :
    Closeable {
    private data class Upstream(
        val destination: MediaDestinationPolicy.ApprovedDestination,
        val proxyAuthorisation: String?,
    )

    private data class Configuration(val policy: MediaDestinationPolicy, val upstream: Upstream?)

    private data class Request(val method: String, val target: String, val version: String, val headers: List<String>)

    private val workers =
        Executors.newFixedThreadPool(64) { task -> Thread(task, "CD-media-proxy-worker").apply { isDaemon = true } }
    @Volatile private var configuration = configuration(policy, upstreamProxy)
    @Volatile private var closed = false
    private val listener = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
    private val acceptThread =
        Thread(::acceptLoop, "CD-media-proxy").apply {
            isDaemon = true
            start()
        }

    @Throws(IOException::class)
    fun reconfigure(policy: MediaDestinationPolicy, upstreamProxy: String?) {
        configuration = configuration(policy, upstreamProxy)
    }

    fun url(): String = "http://127.0.0.1:${listener.localPort}"

    private fun acceptLoop() {
        while (!closed) {
            try {
                val client = listener.accept()
                workers.execute { handle(client) }
            } catch (_: IOException) {
                if (!closed) close()
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            val request = readRequest(client.getInputStream()) ?: return
            val current = configuration
            if (request.method.equals("CONNECT", true)) handleConnect(client, request, current)
            else handleHttp(client, request, current)
        } catch (_: Exception) {
            try {
                sendError(client, 502, "Media destination rejected")
            } catch (_: IOException) {}
        } finally {
            try {
                client.close()
            } catch (_: IOException) {}
        }
    }

    private fun handleConnect(client: Socket, request: Request, current: Configuration) {
        val authority = request.target
        val separator = authority.lastIndexOf(':')
        if (separator <= 0 || separator == authority.length - 1) throw IOException("invalid CONNECT authority")
        var host = authority.substring(0, separator)
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        val port =
            try {
                authority.substring(separator + 1).toInt()
            } catch (e: NumberFormatException) {
                throw IOException("invalid CONNECT port", e)
            }
        val target = current.policy.approve("https://${bracketHost(host)}:$port/")
        openRemote(target, port, current.upstream).use { remote ->
            if (current.upstream != null) {
                val upstreamTarget = MediaDestinationPolicy.addressLiteral(target.addresses()[0]) + ":" + port
                val output = remote.getOutputStream()
                writeAscii(output, "CONNECT $upstreamTarget HTTP/1.1\r\n")
                writeAscii(output, "Host: $authority\r\n")
                if (current.upstream.proxyAuthorisation != null) {
                    writeAscii(output, "Proxy-Authorization: ${current.upstream.proxyAuthorisation}\r\n")
                }
                writeAscii(output, "\r\n")
                val response = readHeaders(remote.getInputStream())
                client.getOutputStream().write(response)
                val status = String(response, StandardCharsets.ISO_8859_1).lineSequence().firstOrNull() ?: ""
                if (!status.contains(" 200 ")) return
            } else {
                writeAscii(client.getOutputStream(), "HTTP/1.1 200 Connection Established\r\n\r\n")
            }
            client.soTimeout = 0
            remote.soTimeout = 0
            relayTunnel(client, remote)
        }
    }

    private fun handleHttp(client: Socket, request: Request, current: Configuration) {
        val target = current.policy.approve(request.target)
        openRemote(target, target.port(), current.upstream).use { remote ->
            val uri = target.uri()
            var path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            if (uri.rawQuery != null) path += "?" + uri.rawQuery
            val requestTarget =
                if (current.upstream == null) path
                else
                    uri.scheme +
                        "://" +
                        MediaDestinationPolicy.addressLiteral(target.addresses()[0]) +
                        ":" +
                        target.port() +
                        path
            val output = remote.getOutputStream()
            writeAscii(output, "${request.method} $requestTarget ${request.version}\r\n")
            for (header in request.headers) {
                val lower = header.lowercase(Locale.ROOT)
                if (
                    lower.startsWith("connection:") ||
                        lower.startsWith("proxy-connection:") ||
                        lower.startsWith("proxy-authorization:")
                )
                    continue
                writeAscii(output, "$header\r\n")
            }
            if (current.upstream?.proxyAuthorisation != null) {
                writeAscii(output, "Proxy-Authorization: ${current.upstream.proxyAuthorisation}\r\n")
            }
            writeAscii(output, "Connection: close\r\n\r\n")
            copyRequestBody(client.getInputStream(), output, request.headers)
            copy(remote.getInputStream(), client.getOutputStream())
        }
    }

    private fun openRemote(target: MediaDestinationPolicy.ApprovedDestination, port: Int, upstream: Upstream?): Socket {
        val connectTarget = upstream?.destination ?: target
        val connectPort = if (upstream == null) port else connectTarget.port()
        var last: IOException? = null
        for (address in connectTarget.addresses()) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, connectPort), CONNECT_TIMEOUT_MILLIS)
                socket.soTimeout = 30_000
                return socket
            } catch (e: IOException) {
                last = e
                try {
                    socket.close()
                } catch (_: IOException) {}
            }
        }
        throw last ?: IOException("media destination did not resolve")
    }

    private fun relayTunnel(client: Socket, remote: Socket) {
        val upstreamRelay = workers.submit {
            try {
                copy(client.getInputStream(), remote.getOutputStream())
            } catch (_: IOException) {} finally {
                try {
                    remote.shutdownOutput()
                } catch (_: IOException) {}
            }
        }
        try {
            copy(remote.getInputStream(), client.getOutputStream())
        } finally {
            upstreamRelay.cancel(true)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            listener.close()
        } catch (_: IOException) {}
        workers.shutdownNow()
        acceptThread.interrupt()
    }

    companion object {
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_REQUEST_BODY_BYTES = 1024 * 1024
        private const val CONNECT_TIMEOUT_MILLIS = 10_000

        private fun configuration(policy: MediaDestinationPolicy, upstreamProxy: String?): Configuration =
            Configuration(policy, parseUpstream(policy, upstreamProxy))

        private fun parseUpstream(policy: MediaDestinationPolicy, configured: String?): Upstream? {
            if (configured == null || configured.all { Character.isWhitespace(it) }) return null
            val configuredUri =
                try {
                    URI(configured)
                } catch (e: URISyntaxException) {
                    throw IOException("providers.yt-dlp-proxy is not a valid URI", e)
                }
            val sanitised =
                try {
                    URI(
                        configuredUri.scheme,
                        null,
                        configuredUri.host,
                        configuredUri.port,
                        configuredUri.path,
                        configuredUri.query,
                        configuredUri.fragment,
                    )
                } catch (e: URISyntaxException) {
                    throw IOException("providers.yt-dlp-proxy is not a valid URI", e)
                }
            val destination = policy.approveConfiguredProxy(sanitised.toString())
            val uri = destination.uri()
            if (
                !uri.scheme.equals("http", true) ||
                    (!uri.path.isNullOrEmpty() && uri.path != "/") ||
                    uri.query != null ||
                    uri.fragment != null
            ) {
                throw IOException("providers.yt-dlp-proxy must be an HTTP proxy URL")
            }
            val authorisation =
                configuredUri.userInfo?.let {
                    "Basic " + Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
                }
            return Upstream(destination, authorisation)
        }

        private fun copyRequestBody(client: InputStream, remote: OutputStream, headers: List<String>) {
            var contentLength = 0L
            for (header in headers) {
                val lower = header.lowercase(Locale.ROOT)
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    throw IOException("chunked proxy request bodies are not supported")
                }
                if (!lower.startsWith("content-length:")) continue
                contentLength =
                    try {
                        header.substring(header.indexOf(':') + 1).trim { it <= ' ' }.toLong()
                    } catch (e: NumberFormatException) {
                        throw IOException("invalid proxy request content length", e)
                    }
            }
            if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES)
                throw IOException("proxy request body is too large")
            val buffer = ByteArray(16 * 1024)
            var remaining = contentLength
            while (remaining > 0) {
                val read = client.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("truncated proxy request body")
                remote.write(buffer, 0, read)
                remaining -= read
            }
            remote.flush()
        }

        private fun readRequest(input: InputStream): Request? {
            val text = String(readHeaders(input), StandardCharsets.ISO_8859_1)
            val lines = text.lines()
            if (lines.isEmpty()) return null
            val first = lines.first().split(" ", limit = 3)
            if (first.size != 3) throw IOException("invalid proxy request line")
            val headers = lines.drop(1).filter { it.isNotEmpty() }
            return Request(first[0], first[1], first[2], headers)
        }

        private fun readHeaders(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            var state = 0
            while (output.size() < MAX_HEADER_BYTES) {
                val value = input.read()
                if (value < 0) break
                output.write(value)
                state =
                    when (state) {
                        0 -> if (value == '\r'.code) 1 else 0
                        1 -> if (value == '\n'.code) 2 else 0
                        2 -> if (value == '\r'.code) 3 else 0
                        3 -> if (value == '\n'.code) 4 else 0
                        else -> state
                    }
                if (state == 4) return output.toByteArray()
            }
            throw IOException("proxy header is missing or too large")
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        }

        private fun sendError(client: Socket?, status: Int, message: String) {
            if (client == null || client.isClosed) return
            writeAscii(
                client.getOutputStream(),
                "HTTP/1.1 $status $message\r\nConnection: close\r\nContent-Length: 0\r\n\r\n",
            )
        }

        private fun writeAscii(output: OutputStream, value: String) {
            output.write(value.toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
        }

        private fun bracketHost(host: String): String = if (':' in host) "[$host]" else host
    }
}
