package net.crabcraft.customdiscs.util;

import net.crabcraft.customdiscs.audio.AudioCapacityRegressionAssertions;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class CustomDiscsSecurityRegressionTest {
  private static final String YOUTUBE_FILTER =
    "https?:\\/\\/(?:www\\.youtube\\.com\\/watch\\?v=|youtu\\.be\\/).+";
  private static final String SOUNDCLOUD_FILTER = "https?:\\/\\/soundcloud\\.com\\/[^\\s]+";

  public static void main(String[] args) throws Exception {
    verifyProviderMatchesWholeUrl();
    verifyPermissionDefaults();
    AudioCapacityRegressionAssertions.verify();
  }

  private static void verifyProviderMatchesWholeUrl() {
    check(RemoteServices.matches("https://www.youtube.com/watch?v=abc", YOUTUBE_FILTER),
      "valid YouTube URLs must remain publicly classifiable");
    check(RemoteServices.matches("https://soundcloud.com/artist/track", SOUNDCLOUD_FILTER),
      "valid SoundCloud URLs must remain publicly classifiable");
    check(!RemoteServices.matches(
        "http://127.0.0.1/?next=https://www.youtube.com/watch?v=abc", YOUTUBE_FILTER),
      "a YouTube-looking substring classified an internal HTTP URL as YouTube");
    check(!RemoteServices.matches(
        "http://169.254.169.254/https://soundcloud.com/artist/track", SOUNDCLOUD_FILTER),
      "a SoundCloud-looking substring classified an internal HTTP URL as SoundCloud");
  }

  private static void verifyPermissionDefaults() throws Exception {
    String pluginYml;
    try (InputStream input = CustomDiscsSecurityRegressionTest.class
      .getClassLoader().getResourceAsStream("plugin.yml")) {
      check(input != null, "bundled plugin.yml is missing");
      pluginYml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    check(permissionDefault(pluginYml, "customdiscs.create.remote.youtube").equals("true"),
      "YouTube creation must remain public by default");
    check(permissionDefault(pluginYml, "customdiscs.create.remote.soundcloud").equals("true"),
      "SoundCloud creation must remain public by default");
    check(permissionDefault(pluginYml, "customdiscs.create.remote.http").equals("op"),
      "generic HTTP creation must be op-only by default");
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
