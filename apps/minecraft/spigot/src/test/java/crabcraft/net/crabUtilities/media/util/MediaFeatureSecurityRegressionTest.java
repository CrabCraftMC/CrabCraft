package crabcraft.net.crabUtilities.media.util;

import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.audio.AudioCapacityRegressionAssertions;
import crabcraft.net.crabUtilities.media.source.MediaSourceKind;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class MediaFeatureSecurityRegressionTest {
  private static final String YOUTUBE_FILTER =
    "https?:\\/\\/(?:www\\.youtube\\.com\\/watch\\?v=|youtu\\.be\\/).+";
  private static final String SOUNDCLOUD_FILTER = "https?:\\/\\/soundcloud\\.com\\/[^\\s]+";

  public static void main(String[] args) throws Exception {
    verifyProviderMatchesWholeUrl();
    verifyPermissionDefaults();
    verifyMessagesAreNotConfigurable();
    verifyEnableOwnsInitialisation();
    verifySinkTimeAuthorisation();
    verifyNumericBounds();
    AudioCapacityRegressionAssertions.verify();
  }

  private static void verifyProviderMatchesWholeUrl() {
    check(MediaSourceKind.matchesEntireSource("https://www.youtube.com/watch?v=abc", YOUTUBE_FILTER),
      "valid YouTube URLs must remain publicly classifiable");
    check(MediaSourceKind.matchesEntireSource("https://soundcloud.com/artist/track", SOUNDCLOUD_FILTER),
      "valid SoundCloud URLs must remain publicly classifiable");
    check(!MediaSourceKind.matchesEntireSource(
        "http://127.0.0.1/?next=https://www.youtube.com/watch?v=abc", YOUTUBE_FILTER),
      "a YouTube-looking substring classified an internal HTTP URL as YouTube");
    check(!MediaSourceKind.matchesEntireSource(
        "http://169.254.169.254/https://soundcloud.com/artist/track", SOUNDCLOUD_FILTER),
      "a SoundCloud-looking substring classified an internal HTTP URL as SoundCloud");
  }

  private static void verifyPermissionDefaults() throws Exception {
    String pluginYml;
    try (InputStream input = MediaFeatureSecurityRegressionTest.class
      .getClassLoader().getResourceAsStream("plugin.yml")) {
      check(input != null, "bundled plugin.yml is missing");
      pluginYml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    check(permissionDefault(pluginYml, "crabutilities.media.create.remote.youtube").equals("true"),
      "YouTube creation must remain public by default");
    check(permissionDefault(pluginYml, "crabutilities.media.create.remote.soundcloud").equals("true"),
      "SoundCloud creation must remain public by default");
    check(permissionDefault(pluginYml, "crabutilities.media.create.remote.http").equals("op"),
      "generic HTTP creation must be op-only by default");
    check(!pluginYml.contains("customdiscs."),
      "legacy permission nodes must not remain public");
    check(!pluginYml.contains("provides: [CustomDiscs]"),
      "Crab Utilities must not advertise the removed standalone plugin identity");
  }

  private static void verifyMessagesAreNotConfigurable() throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    try (InputStream input = MediaFeatureSecurityRegressionTest.class
      .getClassLoader().getResourceAsStream("modules/media.yml")) {
      check(input != null, "bundled modules/media.yml is missing");
      config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    check(!config.contains("media.messages"),
      "media messages remain configurable in the bundled config");
  }

  private static void verifyEnableOwnsInitialisation() throws Exception {
    check(MediaFeature.class.getDeclaredMethod("enable", CrabUtilities.class) != null,
      "media enable no longer accepts the owning plugin instance");
    try {
      MediaFeature.class.getDeclaredMethod("enable");
      throw new AssertionError("media enable can still run without initialising its plugin state");
    } catch (NoSuchMethodException expected) {
      // The lifecycle-safe overload is the only supported entry point.
    }
  }

  private static void verifySinkTimeAuthorisation() {
    Set<String> authorised = Set.of(
      "crabutilities.media.create",
      "crabutilities.media.create.remote",
      "crabutilities.media.create.remote.youtube");
    check(RemoteMediaSecurity.canCreate(authorised::contains, MediaSourceKind.YOUTUBE),
      "fully authorised YouTube creation was rejected");

    check(!RemoteMediaSecurity.canCreate(
        permission -> authorised.contains(permission) && !permission.equals("crabutilities.media.create"),
        MediaSourceKind.YOUTUBE),
      "revoked base permission still authorised a stale dialog callback");
    check(!RemoteMediaSecurity.canCreate(
        permission -> authorised.contains(permission)
          && !permission.equals("crabutilities.media.create.remote"),
        MediaSourceKind.YOUTUBE),
      "revoked remote-media parent still authorised disc or horn creation");
    check(!RemoteMediaSecurity.canCreate(
        permission -> authorised.contains(permission)
          && !permission.equals("crabutilities.media.create.remote.youtube"),
        MediaSourceKind.YOUTUBE),
      "provider-specific policy was not retained");
  }

  private static void verifyNumericBounds() {
    check(RemoteMediaSecurity.isValidDiscSettings(0f, 4, 4, 32),
      "lower configured disc bounds were rejected");
    check(RemoteMediaSecurity.isValidDiscSettings(2f, 32, 4, 32),
      "upper configured disc bounds were rejected");
    check(!RemoteMediaSecurity.isValidDiscSettings(Float.NaN, 24, 4, 32),
      "NaN disc volume was accepted");
    check(!RemoteMediaSecurity.isValidDiscSettings(Float.POSITIVE_INFINITY, 24, 4, 32),
      "infinite disc volume was accepted");
    check(!RemoteMediaSecurity.isValidDiscSettings(Float.MAX_VALUE, 24, 4, 32),
      "oversized disc volume was accepted");
    check(!RemoteMediaSecurity.isValidDiscSettings(1f, 3, 4, 32),
      "below-minimum disc distance was accepted");
    check(!RemoteMediaSecurity.isValidDiscSettings(1f, Integer.MAX_VALUE, 4, 32),
      "oversized disc distance was accepted");
    check(RemoteMediaSecurity.playbackDistance(Integer.MAX_VALUE, 24, 4, 32) == 32,
      "stored oversized distance escaped playback-time bounds");
    check(RemoteMediaSecurity.playbackDistance(0, 24, 4, 32) == 24,
      "legacy default-distance behaviour changed");
  }

  private static String permissionDefault(String yaml, String permission) {
    String header = "  " + permission + ":";
    boolean inPermission = false;
    for (String line : yaml.lines().toList()) {
      if (line.equals(header)) {
        inPermission = true;
        continue;
      }
      if (inPermission && line.startsWith("  ") && !line.startsWith("    ")) break;
      if (!inPermission) continue;
      String trimmed = line.trim();
      if (trimmed.startsWith("default:")) return trimmed.substring("default:".length()).trim();
    }
    throw new AssertionError((inPermission ? "missing default for permission " : "missing permission ")
      + permission);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
