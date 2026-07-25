package crabcraft.net.crabUtilities.media.audio;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback HTTP proxy that resolves every request through {@link MediaDestinationPolicy}. Redirects
 * and nested manifest requests create new proxy requests and are therefore revalidated.
 */
public final class MediaPolicyProxy implements Closeable {
  private static final int MAX_HEADER_BYTES = 32 * 1024;
  private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

  private record Upstream(MediaDestinationPolicy.ApprovedDestination destination,
                          String proxyAuthorisation) {}

  private record Configuration(MediaDestinationPolicy policy, Upstream upstream) {}

  private record Request(String method, String target, String version, List<String> headers) {}

  private final ServerSocket listener;
  private final ExecutorService workers = Executors.newFixedThreadPool(64, r -> {
    Thread thread = new Thread(r, "CD-media-proxy-worker");
    thread.setDaemon(true);
    return thread;
  });
  private final Thread acceptThread;
  private volatile Configuration configuration;
  private volatile boolean closed;

  public MediaPolicyProxy(MediaDestinationPolicy policy, String upstreamProxy) throws IOException {
    this.configuration = configuration(policy, upstreamProxy);
    this.listener = new ServerSocket();
    listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
    this.acceptThread = new Thread(this::acceptLoop, "CD-media-proxy");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  void reconfigure(MediaDestinationPolicy policy, String upstreamProxy) throws IOException {
    configuration = configuration(policy, upstreamProxy);
  }

  public String url() {
    return "http://127.0.0.1:" + listener.getLocalPort();
  }

  private static Configuration configuration(MediaDestinationPolicy policy, String upstreamProxy)
      throws IOException {
    return new Configuration(policy, parseUpstream(policy, upstreamProxy));
  }

  private static Upstream parseUpstream(MediaDestinationPolicy policy, String configured)
      throws IOException {
    if (configured == null || configured.isBlank()) return null;
    final URI configuredUri;
    try {
      configuredUri = new URI(configured);
    } catch (URISyntaxException e) {
      throw new IOException("providers.yt-dlp-proxy is not a valid URI", e);
    }
    final URI sanitised;
    try {
      sanitised = new URI(configuredUri.getScheme(), null, configuredUri.getHost(),
        configuredUri.getPort(), configuredUri.getPath(), configuredUri.getQuery(),
        configuredUri.getFragment());
    } catch (URISyntaxException e) {
      throw new IOException("providers.yt-dlp-proxy is not a valid URI", e);
    }
    MediaDestinationPolicy.ApprovedDestination destination =
      policy.approveConfiguredProxy(sanitised.toString());
    URI uri = destination.uri();
    if (!uri.getScheme().equalsIgnoreCase("http")
        || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
        || uri.getQuery() != null || uri.getFragment() != null) {
      throw new IOException("providers.yt-dlp-proxy must be an HTTP proxy URL");
    }
    String authorisation = null;
    if (configuredUri.getUserInfo() != null) {
      authorisation = "Basic " + Base64.getEncoder().encodeToString(
        configuredUri.getUserInfo().getBytes(StandardCharsets.UTF_8));
    }
    return new Upstream(destination, authorisation);
  }

  private void acceptLoop() {
    while (!closed) {
      try {
        Socket client = listener.accept();
        workers.execute(() -> handle(client));
      } catch (IOException e) {
        if (!closed) close();
      }
    }
  }

  private void handle(Socket client) {
    try {
      client.setSoTimeout(30_000);
      Request request = readRequest(client.getInputStream());
      if (request == null) return;
      Configuration current = configuration;
      if (request.method().equalsIgnoreCase("CONNECT")) handleConnect(client, request, current);
      else handleHttp(client, request, current);
    } catch (Exception e) {
      try {
        sendError(client, 502, "Media destination rejected");
      } catch (IOException ignored) {
      }
    } finally {
      try {
        client.close();
      } catch (IOException ignored) {
      }
    }
  }

  private void handleConnect(Socket client, Request request, Configuration current)
      throws IOException {
    String authority = request.target();
    int separator = authority.lastIndexOf(':');
    if (separator <= 0 || separator == authority.length() - 1) {
      throw new IOException("invalid CONNECT authority");
    }
    String host = authority.substring(0, separator);
    if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
    int port;
    try {
      port = Integer.parseInt(authority.substring(separator + 1));
    } catch (NumberFormatException e) {
      throw new IOException("invalid CONNECT port", e);
    }
    MediaDestinationPolicy.ApprovedDestination target =
      current.policy().approve("https://" + bracketHost(host) + ":" + port + "/");

    try (Socket remote = openRemote(target, port, current.upstream())) {
      if (current.upstream() != null) {
        String upstreamTarget = MediaDestinationPolicy.addressLiteral(target.addresses()[0]) + ":" + port;
        OutputStream out = remote.getOutputStream();
        writeAscii(out, "CONNECT " + upstreamTarget + " HTTP/1.1\r\n");
        writeAscii(out, "Host: " + authority + "\r\n");
        if (current.upstream().proxyAuthorisation() != null) {
          writeAscii(out,
            "Proxy-Authorization: " + current.upstream().proxyAuthorisation() + "\r\n");
        }
        writeAscii(out, "\r\n");
        byte[] response = readHeaders(remote.getInputStream());
        client.getOutputStream().write(response);
        String status = new String(response, StandardCharsets.ISO_8859_1).lines()
          .findFirst().orElse("");
        if (!status.contains(" 200 ")) return;
      } else {
        writeAscii(client.getOutputStream(), "HTTP/1.1 200 Connection Established\r\n\r\n");
      }
      client.setSoTimeout(0);
      remote.setSoTimeout(0);
      relayTunnel(client, remote);
    }
  }

  private void handleHttp(Socket client, Request request, Configuration current) throws IOException {
    MediaDestinationPolicy.ApprovedDestination target =
      current.policy().approve(request.target());
    try (Socket remote = openRemote(target, target.port(), current.upstream())) {
      URI uri = target.uri();
      String path = uri.getRawPath();
      if (path == null || path.isEmpty()) path = "/";
      if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
      String requestTarget = path;
      if (current.upstream() != null) {
        requestTarget = uri.getScheme() + "://"
          + MediaDestinationPolicy.addressLiteral(target.addresses()[0]) + ":" + target.port() + path;
      }

      OutputStream out = remote.getOutputStream();
      writeAscii(out, request.method() + " " + requestTarget + " " + request.version() + "\r\n");
      for (String header : request.headers()) {
        String lower = header.toLowerCase(Locale.ROOT);
        if (lower.startsWith("connection:") || lower.startsWith("proxy-connection:")
            || lower.startsWith("proxy-authorization:")) {
          continue;
        }
        writeAscii(out, header + "\r\n");
      }
      if (current.upstream() != null && current.upstream().proxyAuthorisation() != null) {
        writeAscii(out,
          "Proxy-Authorization: " + current.upstream().proxyAuthorisation() + "\r\n");
      }
      writeAscii(out, "Connection: close\r\n\r\n");
      copyRequestBody(client.getInputStream(), out, request.headers());
      copy(remote.getInputStream(), client.getOutputStream());
    }
  }

  private static void copyRequestBody(InputStream client, OutputStream remote, List<String> headers)
      throws IOException {
    long contentLength = 0;
    for (String header : headers) {
      String lower = header.toLowerCase(Locale.ROOT);
      if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
        throw new IOException("chunked proxy request bodies are not supported");
      }
      if (!lower.startsWith("content-length:")) continue;
      try {
        contentLength = Long.parseLong(header.substring(header.indexOf(':') + 1).trim());
      } catch (NumberFormatException e) {
        throw new IOException("invalid proxy request content length", e);
      }
    }
    if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
      throw new IOException("proxy request body is too large");
    }
    byte[] buffer = new byte[16 * 1024];
    long remaining = contentLength;
    while (remaining > 0) {
      int read = client.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read < 0) throw new IOException("truncated proxy request body");
      remote.write(buffer, 0, read);
      remaining -= read;
    }
    remote.flush();
  }

  private Socket openRemote(MediaDestinationPolicy.ApprovedDestination target, int port,
                            Upstream upstream) throws IOException {
    MediaDestinationPolicy.ApprovedDestination connectTarget =
      upstream == null ? target : upstream.destination();
    int connectPort = upstream == null ? port : connectTarget.port();
    IOException last = null;
    for (InetAddress address : connectTarget.addresses()) {
      Socket socket = new Socket();
      try {
        socket.connect(new InetSocketAddress(address, connectPort), CONNECT_TIMEOUT_MILLIS);
        socket.setSoTimeout(30_000);
        return socket;
      } catch (IOException e) {
        last = e;
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      }
    }
    throw last != null ? last : new IOException("media destination did not resolve");
  }

  private void relayTunnel(Socket client, Socket remote) throws IOException {
    var upstreamRelay = workers.submit(() -> {
      try {
        copy(client.getInputStream(), remote.getOutputStream());
      } catch (IOException ignored) {
      } finally {
        try {
          remote.shutdownOutput();
        } catch (IOException ignored) {
        }
      }
    });
    try {
      copy(remote.getInputStream(), client.getOutputStream());
    } finally {
      upstreamRelay.cancel(true);
    }
  }

  private static Request readRequest(InputStream input) throws IOException {
    byte[] raw = readHeaders(input);
    String text = new String(raw, StandardCharsets.ISO_8859_1);
    List<String> lines = text.lines().toList();
    if (lines.isEmpty()) return null;
    String[] first = lines.getFirst().split(" ", 3);
    if (first.length != 3) throw new IOException("invalid proxy request line");
    List<String> headers = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      if (!lines.get(i).isEmpty()) headers.add(lines.get(i));
    }
    return new Request(first[0], first[1], first[2], headers);
  }

  private static byte[] readHeaders(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int state = 0;
    while (output.size() < MAX_HEADER_BYTES) {
      int value = input.read();
      if (value < 0) break;
      output.write(value);
      state = switch (state) {
        case 0 -> value == '\r' ? 1 : 0;
        case 1 -> value == '\n' ? 2 : 0;
        case 2 -> value == '\r' ? 3 : 0;
        case 3 -> value == '\n' ? 4 : 0;
        default -> state;
      };
      if (state == 4) return output.toByteArray();
    }
    throw new IOException("proxy header is missing or too large");
  }

  private static void copy(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[16 * 1024];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      output.write(buffer, 0, read);
      output.flush();
    }
  }

  private static void sendError(Socket client, int status, String message) throws IOException {
    if (client == null || client.isClosed()) return;
    writeAscii(client.getOutputStream(),
      "HTTP/1.1 " + status + " " + message + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
  }

  private static void writeAscii(OutputStream output, String value) throws IOException {
    output.write(value.getBytes(StandardCharsets.ISO_8859_1));
    output.flush();
  }

  private static String bracketHost(String host) {
    return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      listener.close();
    } catch (IOException ignored) {
    }
    workers.shutdownNow();
    acceptThread.interrupt();
  }
}
