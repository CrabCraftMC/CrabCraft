package crabcraft.net.crabUtilities.media.audio;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Resolves and approves untrusted media destinations before a network-capable subprocess can use
 * them. The approved addresses are returned so callers can connect without a second DNS lookup.
 */
public final class MediaDestinationPolicy {
  @FunctionalInterface
  interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  public record ApprovedDestination(URI uri, InetAddress[] addresses) {
    public ApprovedDestination {
      addresses = addresses.clone();
    }

    @Override
    public InetAddress[] addresses() {
      return addresses.clone();
    }

    public int port() {
      if (uri.getPort() >= 0) return uri.getPort();
      return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }
  }

  private final Resolver resolver;

  public MediaDestinationPolicy() {
    this(InetAddress::getAllByName);
  }

  MediaDestinationPolicy(Resolver resolver) {
    this.resolver = resolver;
  }

  public ApprovedDestination approve(String value) throws IOException {
    return approve(value, false);
  }

  ApprovedDestination approveConfiguredProxy(String value) throws IOException {
    return approve(value, true);
  }

  private ApprovedDestination approve(String value, boolean configuredProxy) throws IOException {
    final URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException | NullPointerException e) {
      throw new IOException("media destination is not a valid URI", e);
    }

    String scheme = uri.getScheme();
    String host = uri.getHost();
    if (scheme == null || host == null
        || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        || uri.getUserInfo() != null) {
      throw new IOException("media destination must be an absolute HTTP(S) URI without credentials");
    }
    if (uri.getPort() < -1 || uri.getPort() > 65535) {
      throw new IOException("media destination has an invalid port");
    }

    InetAddress[] addresses = resolver.resolve(host);
    if (addresses.length == 0) throw new IOException("media destination did not resolve");
    if (!configuredProxy
        && Arrays.stream(addresses).anyMatch(MediaDestinationPolicy::isProtected)) {
      throw new IOException("media destination resolves to a protected network");
    }
    return new ApprovedDestination(uri, addresses);
  }

  static boolean isProtected(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = bytes[0] & 0xff;
      int second = bytes[1] & 0xff;
      return first == 0
        || first == 10
        || first == 127
        || (first == 100 && second >= 64 && second <= 127)
        || (first == 169 && second == 254)
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192 && (second == 0 || second == 168))
        || (first == 198 && (second == 18 || second == 19))
        || first >= 224;
    }
    if (address instanceof Inet6Address) {
      int first = bytes[0] & 0xff;
      int second = bytes[1] & 0xff;
      // Global unicast (2000::/3) is public. The IPv4-mapped form is normally exposed by Java as
      // Inet4Address; all remaining special-purpose IPv6 ranges fail closed.
      return (first & 0xe0) != 0x20
        || (first == 0x20 && second == 0x01
          && bytes[2] == 0x0d && (bytes[3] & 0xff) == 0xb8);
    }
    return true;
  }

  static String addressLiteral(InetAddress address) {
    String host = address.getHostAddress();
    int scope = host.indexOf('%');
    if (scope >= 0) host = host.substring(0, scope);
    return address instanceof Inet6Address ? "[" + host.toLowerCase(Locale.ROOT) + "]" : host;
  }
}
