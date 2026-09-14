package crabcraft.net.crabUtilities.media.util

import crabcraft.net.crabUtilities.CrabUtilities
import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.audio.AudioCapacityRegressionAssertions
import crabcraft.net.crabUtilities.media.source.MediaSourceKind
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.charset.StandardCharsets

object MediaFeatureSecurityRegressionTest {
  private const val YOUTUBE_FILTER =
    "https?:\\/\\/(?:www\\.youtube\\.com\\/watch\\?v=|youtu\\.be\\/).+"
  private const val SOUNDCLOUD_FILTER = "https?:\\/\\/soundcloud\\.com\\/[^\\s]+"

  @JvmStatic
  fun main(args: Array<String>) {
    verifyProviderMatchesWholeUrl()
    verifyPermissionDefaults()
    verifyMessagesAreNotConfigurable()
    verifyEnableOwnsInitialisation()
    verifySinkTimeAuthorisation()
    verifyNumericBounds()
    AudioCapacityRegressionAssertions.verify()
  }

  private fun verifyProviderMatchesWholeUrl() {
    check(MediaSourceKind.matchesEntireSource("https://www.youtube.com/watch?v=abc", YOUTUBE_FILTER),
      "valid YouTube URLs must remain publicly classifiable")
    check(MediaSourceKind.matchesEntireSource("https://soundcloud.com/artist/track", SOUNDCLOUD_FILTER),
      "valid SoundCloud URLs must remain publicly classifiable")
    check(!MediaSourceKind.matchesEntireSource(
        "http://127.0.0.1/?next=https://www.youtube.com/watch?v=abc", YOUTUBE_FILTER),
      "a YouTube-looking substring classified an internal HTTP URL as YouTube")
    check(!MediaSourceKind.matchesEntireSource(
        "http://169.254.169.254/https://soundcloud.com/artist/track", SOUNDCLOUD_FILTER),
      "a SoundCloud-looking substring classified an internal HTTP URL as SoundCloud")
  }

  private fun verifyPermissionDefaults() {
    val pluginYml = MediaFeatureSecurityRegressionTest::class.java
      .classLoader.getResourceAsStream("plugin.yml").use { input ->
        check(input != null, "bundled plugin.yml is missing")
        String(input!!.readAllBytes(), StandardCharsets.UTF_8)
      }

    check(permissionDefault(pluginYml, "crabutilities.media.create.remote.youtube") == "true",
      "YouTube creation must remain public by default")
    check(permissionDefault(pluginYml, "crabutilities.media.create.remote.soundcloud") == "true",
      "SoundCloud creation must remain public by default")
    check(permissionDefault(pluginYml, "crabutilities.media.create.remote.http") == "op",
      "generic HTTP creation must be op-only by default")
    check(!pluginYml.contains("customdiscs."),
      "legacy permission nodes must not remain public")
    check(!pluginYml.contains("provides: [CustomDiscs]"),
      "Crab Utilities must not advertise the removed standalone plugin identity")
  }

  private fun verifyMessagesAreNotConfigurable() {
    val config = YamlConfiguration()
    MediaFeatureSecurityRegressionTest::class.java
      .classLoader.getResourceAsStream("modules/media.yml").use { input ->
        check(input != null, "bundled modules/media.yml is missing")
        config.loadFromString(String(input!!.readAllBytes(), StandardCharsets.UTF_8))
      }
    check(!config.contains("media.messages"),
      "media messages remain configurable in the bundled config")
  }

  private fun verifyEnableOwnsInitialisation() {
    check(MediaFeature::class.java.getDeclaredMethod("enable", CrabUtilities::class.java) != null,
      "media enable no longer accepts the owning plugin instance")
    try {
      MediaFeature::class.java.getDeclaredMethod("enable")
      throw AssertionError("media enable can still run without initialising its plugin state")
    } catch (expected: NoSuchMethodException) {
      // The lifecycle-safe overload is the only supported entry point.
    }
  }

  private fun verifySinkTimeAuthorisation() {
    val authorised = setOf(
      "crabutilities.media.create",
      "crabutilities.media.create.remote",
      "crabutilities.media.create.remote.youtube")
    check(RemoteMediaSecurity.canCreate(authorised::contains, MediaSourceKind.YOUTUBE),
      "fully authorised YouTube creation was rejected")

    check(!RemoteMediaSecurity.canCreate(
        { permission -> permission in authorised && permission != "crabutilities.media.create" },
        MediaSourceKind.YOUTUBE),
      "revoked base permission still authorised a stale dialog callback")
    check(!RemoteMediaSecurity.canCreate(
        { permission -> permission in authorised
          && permission != "crabutilities.media.create.remote" },
        MediaSourceKind.YOUTUBE),
      "revoked remote-media parent still authorised disc or horn creation")
    check(!RemoteMediaSecurity.canCreate(
        { permission -> permission in authorised
          && permission != "crabutilities.media.create.remote.youtube" },
        MediaSourceKind.YOUTUBE),
      "provider-specific policy was not retained")
  }

  private fun verifyNumericBounds() {
    check(RemoteMediaSecurity.isValidDiscSettings(0f, 4, 4, 32),
      "lower configured disc bounds were rejected")
    check(RemoteMediaSecurity.isValidDiscSettings(2f, 32, 4, 32),
      "upper configured disc bounds were rejected")
    check(!RemoteMediaSecurity.isValidDiscSettings(Float.NaN, 24, 4, 32),
      "NaN disc volume was accepted")
    check(!RemoteMediaSecurity.isValidDiscSettings(Float.POSITIVE_INFINITY, 24, 4, 32),
      "infinite disc volume was accepted")
    check(!RemoteMediaSecurity.isValidDiscSettings(Float.MAX_VALUE, 24, 4, 32),
      "oversized disc volume was accepted")
    check(!RemoteMediaSecurity.isValidDiscSettings(1f, 3, 4, 32),
      "below-minimum disc distance was accepted")
    check(!RemoteMediaSecurity.isValidDiscSettings(1f, Int.MAX_VALUE, 4, 32),
      "oversized disc distance was accepted")
    check(RemoteMediaSecurity.playbackDistance(Int.MAX_VALUE, 24, 4, 32) == 32,
      "stored oversized distance escaped playback-time bounds")
    check(RemoteMediaSecurity.playbackDistance(0, 24, 4, 32) == 24,
      "legacy default-distance behaviour changed")
  }

  private fun permissionDefault(yaml: String, permission: String): String {
    val header = "  " + permission + ":"
    var inPermission = false
    for (line in yaml.lines()) {
      if (line == header) {
        inPermission = true
        continue
      }
      if (inPermission && line.startsWith("  ") && !line.startsWith("    ")) break
      if (!inPermission) continue
      val trimmed = line.trim()
      if (trimmed.startsWith("default:")) return trimmed.substring("default:".length).trim()
    }
    throw AssertionError((if (inPermission) "missing default for permission " else "missing permission ")
      + permission)
  }

  private fun check(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
  }
}
