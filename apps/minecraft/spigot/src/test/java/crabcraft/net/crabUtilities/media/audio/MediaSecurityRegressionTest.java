package crabcraft.net.crabUtilities.media.audio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class MediaSecurityRegressionTest {
  public static void main(String[] args) throws Exception {
    verifyStructuredResolverOutput();
    verifyProtectedDestinations();
    verifyProtectedUpstreamProxyIsSeparatedFromDestinations();
    verifyRedirectRequestsAreRevalidated();
    verifyPolicyProxyCanBeReloaded();
    verifyFfmpegIsConstrainedToThePolicyProxy();
  }

  private static void verifyStructuredResolverOutput() throws Exception {
    TrackResolver.ResolvedTrack track = TrackResolver.parseOutput("""
      {"title":"http://127.0.0.1:8080/admin","url":"https://93.184.216.34/audio","duration":12.9}
      """);
    check(track.title().equals("http://127.0.0.1:8080/admin"),
      "URL-shaped remote title was not preserved as title metadata");
    check(track.streamUrl().equals("https://93.184.216.34/audio"),
      "URL-shaped remote title was confused with the structured stream URL");
    check(track.durationSeconds() == 12, "structured duration was not parsed");

    List<String> command = TrackResolver.command(
      "yt-dlp", "", "http://127.0.0.1:12345", "https://www.youtube.com/watch?v=abc");
    int printOption = command.indexOf("--print");
    check(printOption >= 0
        && command.get(printOption + 1).equals("%(.{title,url,duration})j"),
      "yt-dlp is not constrained to one provenance-preserving JSON object");
    int proxyOption = command.indexOf("--proxy");
    check(proxyOption >= 0 && command.get(proxyOption + 1).equals("http://127.0.0.1:12345"),
      "yt-dlp is not forced through the destination-policy proxy");
    check(command.contains("--ignore-config"),
      "local yt-dlp configuration can override the secured resolver command");
    check(command.get(command.size() - 2).equals("--"),
      "stored URLs can be parsed as yt-dlp options");

    expectRejected(() -> TrackResolver.parseOutput("""
      http://127.0.0.1:8080/admin
      https://93.184.216.34/audio
      12
      """), "legacy ambiguous line output was accepted");
    expectRejected(() -> TrackResolver.parseOutput(
      "{\"title\":\"track\",\"duration\":12}"), "metadata without a stream URL was accepted");
  }

  private static void verifyProtectedDestinations() throws Exception {
    MediaDestinationPolicy policy = new MediaDestinationPolicy();
    policy.approve("https://93.184.216.34/audio");
    for (String destination : List.of(
      "http://0.0.0.0/audio",
      "http://10.0.0.1/audio",
      "http://100.109.83.5/audio",
      "http://127.0.0.1/audio",
      "http://169.254.169.254/latest/meta-data/",
      "http://224.0.0.1/audio",
      "http://[::1]/audio",
      "http://[fc00::1]/audio",
      "http://[fe80::1]/audio")) {
      expectRejected(() -> policy.approve(destination),
        "protected destination was accepted: " + destination);
    }
    expectRejected(() -> policy.approve("file:///etc/passwd"),
      "non-HTTP media protocol was accepted");

    MediaDestinationPolicy rebindingPolicy = new MediaDestinationPolicy(host -> new InetAddress[]{
      InetAddress.getByName("93.184.216.34"),
      InetAddress.getByName("127.0.0.1")
    });
    expectRejected(() -> rebindingPolicy.approve("https://media.example/audio"),
      "mixed public/private DNS result was accepted");
  }

  private static void verifyProtectedUpstreamProxyIsSeparatedFromDestinations() throws Exception {
    new MediaDestinationPolicy().approveConfiguredProxy("http://100.109.83.5:8888");

    try (ServerSocket upstream = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
         MediaPolicyProxy proxy = new MediaPolicyProxy(
           new MediaDestinationPolicy(),
           "http://127.0.0.1:" + upstream.getLocalPort())) {
      AtomicReference<Throwable> responderFailure = new AtomicReference<>();
      Thread responder = new Thread(() -> {
        try (Socket socket = upstream.accept()) {
          readRequestHeaders(socket);
          socket.getOutputStream().write(
            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
        } catch (Throwable t) {
          responderFailure.set(t);
        }
      }, "protected-media-upstream-test");
      responder.start();

      check(proxyRequest(proxy, "http://127.0.0.1/private").startsWith("HTTP/1.1 502"),
        "a protected player-controlled destination reached the trusted upstream proxy");
      check(proxyRequest(proxy, "http://93.184.216.34/audio").startsWith("HTTP/1.1 204"),
        "the administrator-configured protected upstream proxy was not usable");
      responder.join(2_000);
      check(!responder.isAlive(), "the policy proxy did not reach the configured upstream");
      check(responderFailure.get() == null,
        "the configured upstream proxy request failed: " + responderFailure.get());
    }
  }

  private static void verifyRedirectRequestsAreRevalidated() throws Exception {
    MediaDestinationPolicy policy = new MediaDestinationPolicy();
    try (MediaPolicyProxy proxy = new MediaPolicyProxy(policy, "")) {
      URI proxyUri = URI.create(proxy.url());
      try (Socket socket = new Socket(proxyUri.getHost(), proxyUri.getPort())) {
        socket.getOutputStream().write((
          "GET http://169.254.169.254/latest/meta-data/ HTTP/1.1\r\n"
            + "Host: 169.254.169.254\r\n"
            + "Connection: close\r\n\r\n"
        ).getBytes(StandardCharsets.ISO_8859_1));
        String response = new String(socket.getInputStream().readNBytes(256),
          StandardCharsets.ISO_8859_1);
        check(response.startsWith("HTTP/1.1 502"),
          "policy proxy did not reject a protected redirect or nested-resource request");
      }
      try (Socket socket = new Socket(proxyUri.getHost(), proxyUri.getPort())) {
        socket.getOutputStream().write((
          "CONNECT 127.0.0.1:443 HTTP/1.1\r\n"
            + "Host: 127.0.0.1:443\r\n\r\n"
        ).getBytes(StandardCharsets.ISO_8859_1));
        String response = new String(socket.getInputStream().readNBytes(256),
          StandardCharsets.ISO_8859_1);
        check(response.startsWith("HTTP/1.1 502"),
          "policy proxy did not reject a protected HTTPS redirect");
      }
    }
  }

  private static void verifyPolicyProxyCanBeReloaded() throws Exception {
    try (ServerSocket destination = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
         MediaPolicyProxy proxy = new MediaPolicyProxy(new MediaDestinationPolicy(), "")) {
      AtomicReference<Throwable> responderFailure = new AtomicReference<>();
      Thread responder = new Thread(() -> {
        try (Socket socket = destination.accept()) {
          readRequestHeaders(socket);
          socket.getOutputStream().write(
            "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
        } catch (Throwable t) {
          responderFailure.set(t);
        }
      }, "media-policy-reload-test");
      responder.start();

      String target = "http://127.0.0.1:" + destination.getLocalPort() + "/audio";
      check(proxyRequest(proxy, target).startsWith("HTTP/1.1 502"),
        "the initial protected-network policy was not enforced");

      expectRejected(() -> proxy.reconfigure(new MediaDestinationPolicy(), "not a URI"),
        "an invalid upstream proxy configuration was accepted");
      check(proxyRequest(proxy, target).startsWith("HTTP/1.1 502"),
        "a failed reload replaced the previous restrictive policy");

      proxy.reconfigure(new MediaDestinationPolicy(), "");
      check(proxyRequest(proxy, target).startsWith("HTTP/1.1 502"),
        "a valid reload weakened protected destination filtering");
      destination.close();
      responder.join(2_000);
      check(!responder.isAlive(), "the protected destination was unexpectedly reached");
      check(responderFailure.get() instanceof IOException,
        "the protected destination listener accepted a connection");
    }
  }

  private static void readRequestHeaders(Socket socket) throws IOException {
    int state = 0;
    while (state < 4) {
      int value = socket.getInputStream().read();
      if (value < 0) throw new IOException("proxy request ended before its headers");
      state = switch (state) {
        case 0 -> value == '\r' ? 1 : 0;
        case 1 -> value == '\n' ? 2 : 0;
        case 2 -> value == '\r' ? 3 : 0;
        case 3 -> value == '\n' ? 4 : 0;
        default -> state;
      };
    }
  }

  private static String proxyRequest(MediaPolicyProxy proxy, String target) throws Exception {
    URI proxyUri = URI.create(proxy.url());
    try (Socket socket = new Socket(proxyUri.getHost(), proxyUri.getPort())) {
      socket.getOutputStream().write((
        "GET " + target + " HTTP/1.1\r\n"
          + "Host: 127.0.0.1\r\n"
          + "Connection: close\r\n\r\n"
      ).getBytes(StandardCharsets.ISO_8859_1));
      return new String(socket.getInputStream().readNBytes(256),
        StandardCharsets.ISO_8859_1);
    }
  }

  private static void verifyFfmpegIsConstrainedToThePolicyProxy() {
    List<String> command = FfmpegPcmStream.command(
      "ffmpeg", "https://93.184.216.34/audio", 1f, "http://127.0.0.1:12345", 7);
    check(command.contains("-protocol_whitelist"), "FFmpeg protocol whitelist is missing");
    check(command.contains("http,https,tls,tcp,crypto,httpproxy,data"),
      "FFmpeg can use an unexpected nested network protocol");
    int proxyOption = command.indexOf("-http_proxy");
    check(proxyOption >= 0 && command.get(proxyOption + 1).equals("http://127.0.0.1:12345"),
      "FFmpeg is not forced through the destination-policy proxy");
    check(command.contains("-rw_timeout"), "FFmpeg network timeout was removed");
  }

  private static void expectRejected(ThrowingRunnable runnable, String message) throws Exception {
    try {
      runnable.run();
    } catch (IOException expected) {
      return;
    }
    throw new AssertionError(message);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
