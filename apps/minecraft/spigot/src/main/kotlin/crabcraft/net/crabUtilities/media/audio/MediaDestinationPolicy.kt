package crabcraft.net.crabUtilities.media.audio

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.Locale

/** Resolves untrusted media destinations and returns approved addresses to avoid a second DNS lookup. */
class MediaDestinationPolicy private constructor(
  private val resolver: Resolver,
  private val allowProtectedNetworks: Boolean
) {
  fun interface Resolver {
    @Throws(UnknownHostException::class) fun resolve(host: String): Array<InetAddress>
  }

  data class ApprovedDestination(private val uri: URI, private var addresses: Array<InetAddress>) {
    init { addresses = addresses.clone() }
    fun uri(): URI = uri
    fun addresses(): Array<InetAddress> = addresses.clone()
    fun port(): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
  }

  constructor() : this(Resolver(InetAddress::getAllByName), false)
  constructor(resolver: Resolver) : this(resolver, false)

  @Throws(IOException::class)
  fun approve(value: String?): ApprovedDestination = approve(value, allowProtectedNetworks)

  @Throws(IOException::class)
  fun approveConfiguredProxy(value: String?): ApprovedDestination = approve(value, true)

  @Throws(IOException::class)
  private fun approve(value: String?, allowProtected: Boolean): ApprovedDestination {
    val uri = try {
      URI(value)
    } catch (e: URISyntaxException) {
      throw IOException("media destination is not a valid URI", e)
    } catch (e: NullPointerException) {
      throw IOException("media destination is not a valid URI", e)
    }
    val scheme = uri.scheme
    val host = uri.host
    if (scheme == null || host == null ||
      !(scheme.equals("http", true) || scheme.equals("https", true)) || uri.userInfo != null) {
      throw IOException("media destination must be an absolute HTTP(S) URI without credentials")
    }
    if (uri.port < -1 || uri.port > 65535) throw IOException("media destination has an invalid port")
    val addresses = resolver.resolve(host)
    if (addresses.isEmpty()) throw IOException("media destination did not resolve")
    if (!allowProtected && addresses.any(::isProtected)) {
      throw IOException("media destination resolves to a protected network")
    }
    return ApprovedDestination(uri, addresses)
  }

  companion object {
    /** Protected-network exception used only by the administrator-owned lofi source. */
    @JvmStatic fun forTrustedLofiConfiguration(): MediaDestinationPolicy =
      MediaDestinationPolicy(Resolver(InetAddress::getAllByName), true)

    @JvmStatic fun isProtected(address: InetAddress): Boolean {
      if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return true
      val bytes = address.address
      if (address is Inet4Address) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 0 || first == 10 || first == 127 ||
          (first == 100 && second in 64..127) || (first == 169 && second == 254) ||
          (first == 172 && second in 16..31) || (first == 192 && (second == 0 || second == 168)) ||
          (first == 198 && (second == 18 || second == 19)) || first >= 224
      }
      if (address is Inet6Address) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        // Global unicast (2000::/3) is public. Java normally exposes IPv4-mapped addresses as IPv4;
        // all remaining special-purpose IPv6 ranges fail closed.
        return (first and 0xe0) != 0x20 || (first == 0x20 && second == 0x01 &&
          bytes[2].toInt() == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8)
      }
      return true
    }

    @JvmStatic fun addressLiteral(address: InetAddress): String {
      var host = address.hostAddress
      val scope = host.indexOf('%')
      if (scope >= 0) host = host.substring(0, scope)
      return if (address is Inet6Address) "[" + host.lowercase(Locale.ROOT) + "]" else host
    }
  }
}
