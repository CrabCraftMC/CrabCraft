package crabcraft.net.crabUtilities.media.audio

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

object MediaSecurityRegressionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        verifyStructuredResolverOutput()
        verifyProtectedDestinations()
        verifyTrustedConfigurationDestinations()
        verifyProtectedUpstreamProxyIsSeparatedFromDestinations()
        verifyRedirectRequestsAreRevalidated()
        verifyPolicyProxyCanBeReloaded()
        verifyFfmpegIsConstrainedToThePolicyProxy()
    }

    private fun verifyStructuredResolverOutput() {
        val track =
            TrackResolver.parseOutput(
                """
                {"title":"http://127.0.0.1:8080/admin","url":"https://93.184.216.34/audio","duration":12.9}
                """
                    .trimIndent()
            )
        check(
            track.title() == "http://127.0.0.1:8080/admin",
            "URL-shaped remote title was not preserved as title metadata",
        )
        check(
            track.streamUrl() == "https://93.184.216.34/audio",
            "URL-shaped remote title was confused with the structured stream URL",
        )
        check(track.durationSeconds() == 12, "structured duration was not parsed")
        val command =
            TrackResolver.command("yt-dlp", "", "http://127.0.0.1:12345", "https://www.youtube.com/watch?v=abc")
        val printOption = command.indexOf("--print")
        check(
            printOption >= 0 && command[printOption + 1] == "%(.{title,url,duration})j",
            "yt-dlp is not constrained to one provenance-preserving JSON object",
        )
        val proxyOption = command.indexOf("--proxy")
        check(
            proxyOption >= 0 && command[proxyOption + 1] == "http://127.0.0.1:12345",
            "yt-dlp is not forced through the destination-policy proxy",
        )
        check("--ignore-config" in command, "local yt-dlp configuration can override the secured resolver command")
        check(command[command.size - 2] == "--", "stored URLs can be parsed as yt-dlp options")
        expectRejected("legacy ambiguous line output was accepted") {
            TrackResolver.parseOutput(
                """
                http://127.0.0.1:8080/admin
                https://93.184.216.34/audio
                12
                """
                    .trimIndent()
            )
        }
        expectRejected("metadata without a stream URL was accepted") {
            TrackResolver.parseOutput("{\"title\":\"track\",\"duration\":12}")
        }
    }

    private fun verifyProtectedDestinations() {
        val policy = MediaDestinationPolicy()
        policy.approve("https://93.184.216.34/audio")
        for (destination in
            listOf(
                "http://0.0.0.0/audio",
                "http://10.0.0.1/audio",
                "http://100.109.83.5/audio",
                "http://127.0.0.1/audio",
                "http://169.254.169.254/latest/meta-data/",
                "http://224.0.0.1/audio",
                "http://[::1]/audio",
                "http://[fc00::1]/audio",
                "http://[fe80::1]/audio",
            )) {
            expectRejected("protected destination was accepted: $destination") { policy.approve(destination) }
        }
        expectRejected("non-HTTP media protocol was accepted") { policy.approve("file:///etc/passwd") }
        val rebindingPolicy = MediaDestinationPolicy { _ ->
            arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1"))
        }
        expectRejected("mixed public/private DNS result was accepted") {
            rebindingPolicy.approve("https://media.example/audio")
        }
    }

    private fun verifyTrustedConfigurationDestinations() {
        val trustedPolicy = MediaDestinationPolicy.forTrustedLofiConfiguration()
        trustedPolicy.approve("http://100.109.83.5/audio")
        trustedPolicy.approve("http://127.0.0.1/audio")
        expectRejected("trusted configuration accepted a non-HTTP media protocol") {
            trustedPolicy.approve("file:///etc/passwd")
        }
        expectRejected("trusted configuration accepted URL credentials") {
            trustedPolicy.approve("http://user:password@127.0.0.1/audio")
        }
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { destination ->
            MediaPolicyProxy(trustedPolicy, "").use { proxy ->
                val responderFailure = AtomicReference<Throwable>()
                val responder =
                    Thread(
                        {
                            try {
                                destination.accept().use { socket ->
                                    readRequestHeaders(socket)
                                    socket
                                        .getOutputStream()
                                        .write(
                                            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                                                .toByteArray(StandardCharsets.ISO_8859_1)
                                        )
                                }
                            } catch (t: Throwable) {
                                responderFailure.set(t)
                            }
                        },
                        "trusted-config-media-test",
                    )
                responder.start()
                val target = "http://127.0.0.1:${destination.localPort}/audio"
                check(
                    proxyRequest(proxy, target).startsWith("HTTP/1.1 204"),
                    "the trusted configuration proxy rejected a protected destination",
                )
                responder.join(2_000)
                check(!responder.isAlive, "the trusted configuration proxy did not reach its destination")
                check(
                    responderFailure.get() == null,
                    "the trusted configuration proxy request failed: ${responderFailure.get()}",
                )
            }
        }
    }

    private fun verifyProtectedUpstreamProxyIsSeparatedFromDestinations() {
        MediaDestinationPolicy().approveConfiguredProxy("http://100.109.83.5:8888")
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { upstream ->
            MediaPolicyProxy(MediaDestinationPolicy(), "http://127.0.0.1:${upstream.localPort}").use { proxy ->
                val responderFailure = AtomicReference<Throwable>()
                val responder =
                    Thread(
                        {
                            try {
                                upstream.accept().use { socket ->
                                    readRequestHeaders(socket)
                                    socket
                                        .getOutputStream()
                                        .write(
                                            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                                                .toByteArray(StandardCharsets.ISO_8859_1)
                                        )
                                }
                            } catch (t: Throwable) {
                                responderFailure.set(t)
                            }
                        },
                        "protected-media-upstream-test",
                    )
                responder.start()
                check(
                    proxyRequest(proxy, "http://127.0.0.1/private").startsWith("HTTP/1.1 502"),
                    "a protected player-controlled destination reached the trusted upstream proxy",
                )
                check(
                    proxyRequest(proxy, "http://93.184.216.34/audio").startsWith("HTTP/1.1 204"),
                    "the administrator-configured protected upstream proxy was not usable",
                )
                responder.join(2_000)
                check(!responder.isAlive, "the policy proxy did not reach the configured upstream")
                check(
                    responderFailure.get() == null,
                    "the configured upstream proxy request failed: ${responderFailure.get()}",
                )
            }
        }
    }

    private fun verifyRedirectRequestsAreRevalidated() {
        MediaPolicyProxy(MediaDestinationPolicy(), "").use { proxy ->
            val proxyUri = URI.create(proxy.url())
            Socket(proxyUri.host, proxyUri.port).use { socket ->
                socket
                    .getOutputStream()
                    .write(
                        ("GET http://169.254.169.254/latest/meta-data/ HTTP/1.1\r\n" +
                                "Host: 169.254.169.254\r\nConnection: close\r\n\r\n")
                            .toByteArray(StandardCharsets.ISO_8859_1)
                    )
                val response = String(socket.getInputStream().readNBytes(256), StandardCharsets.ISO_8859_1)
                check(
                    response.startsWith("HTTP/1.1 502"),
                    "policy proxy did not reject a protected redirect or nested-resource request",
                )
            }
            Socket(proxyUri.host, proxyUri.port).use { socket ->
                socket
                    .getOutputStream()
                    .write(
                        ("CONNECT 127.0.0.1:443 HTTP/1.1\r\n" + "Host: 127.0.0.1:443\r\n\r\n").toByteArray(
                            StandardCharsets.ISO_8859_1
                        )
                    )
                val response = String(socket.getInputStream().readNBytes(256), StandardCharsets.ISO_8859_1)
                check(response.startsWith("HTTP/1.1 502"), "policy proxy did not reject a protected HTTPS redirect")
            }
        }
    }

    private fun verifyPolicyProxyCanBeReloaded() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { destination ->
            MediaPolicyProxy(MediaDestinationPolicy(), "").use { proxy ->
                val responderFailure = AtomicReference<Throwable>()
                val responder =
                    Thread(
                        {
                            try {
                                destination.accept().use { socket ->
                                    readRequestHeaders(socket)
                                    socket
                                        .getOutputStream()
                                        .write(
                                            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
                                                .toByteArray(StandardCharsets.ISO_8859_1)
                                        )
                                }
                            } catch (t: Throwable) {
                                responderFailure.set(t)
                            }
                        },
                        "media-policy-reload-test",
                    )
                responder.start()
                val target = "http://127.0.0.1:${destination.localPort}/audio"
                check(
                    proxyRequest(proxy, target).startsWith("HTTP/1.1 502"),
                    "the initial protected-network policy was not enforced",
                )
                expectRejected("an invalid upstream proxy configuration was accepted") {
                    proxy.reconfigure(MediaDestinationPolicy(), "not a URI")
                }
                check(
                    proxyRequest(proxy, target).startsWith("HTTP/1.1 502"),
                    "a failed reload replaced the previous restrictive policy",
                )
                proxy.reconfigure(MediaDestinationPolicy(), "")
                check(
                    proxyRequest(proxy, target).startsWith("HTTP/1.1 502"),
                    "a valid reload weakened protected destination filtering",
                )
                destination.close()
                responder.join(2_000)
                check(!responder.isAlive, "the protected destination was unexpectedly reached")
                check(responderFailure.get() is IOException, "the protected destination listener accepted a connection")
            }
        }
    }

    private fun readRequestHeaders(socket: Socket) {
        var state = 0
        while (state < 4) {
            val value = socket.getInputStream().read()
            if (value < 0) throw IOException("proxy request ended before its headers")
            state =
                when (state) {
                    0 -> if (value == '\r'.code) 1 else 0
                    1 -> if (value == '\n'.code) 2 else 0
                    2 -> if (value == '\r'.code) 3 else 0
                    3 -> if (value == '\n'.code) 4 else 0
                    else -> state
                }
        }
    }

    private fun proxyRequest(proxy: MediaPolicyProxy, target: String): String {
        val proxyUri = URI.create(proxy.url())
        Socket(proxyUri.host, proxyUri.port).use { socket ->
            socket
                .getOutputStream()
                .write(
                    ("GET $target HTTP/1.1\r\n" + "Host: 127.0.0.1\r\nConnection: close\r\n\r\n").toByteArray(
                        StandardCharsets.ISO_8859_1
                    )
                )
            return String(socket.getInputStream().readNBytes(256), StandardCharsets.ISO_8859_1)
        }
    }

    private fun verifyFfmpegIsConstrainedToThePolicyProxy() {
        val command = FfmpegPcmStream.command("ffmpeg", "https://93.184.216.34/audio", 1f, "http://127.0.0.1:12345", 7)
        check("-protocol_whitelist" in command, "FFmpeg protocol whitelist is missing")
        check(
            "http,https,tls,tcp,crypto,httpproxy,data" in command,
            "FFmpeg can use an unexpected nested network protocol",
        )
        val proxyOption = command.indexOf("-http_proxy")
        check(
            proxyOption >= 0 && command[proxyOption + 1] == "http://127.0.0.1:12345",
            "FFmpeg is not forced through the destination-policy proxy",
        )
        check("-rw_timeout" in command, "FFmpeg network timeout was removed")
    }

    private fun expectRejected(message: String, runnable: () -> Unit) {
        try {
            runnable()
        } catch (_: IOException) {
            return
        }
        throw AssertionError(message)
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }
}
