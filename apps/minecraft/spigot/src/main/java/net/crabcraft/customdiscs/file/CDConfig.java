package net.crabcraft.customdiscs.file;

import lombok.Getter;
import org.simpleyaml.configuration.comments.CommentType;
import org.simpleyaml.configuration.file.YamlFile;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.language.Language;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

@Getter
@SuppressWarnings("unused")
public class CDConfig {
  private final YamlFile yaml = new YamlFile();
  private final File configFile;
  private String configVersion;

  public CDConfig(File configFile) {
    this.configFile = configFile;
  }

  public void load() {
    if (configFile.exists()) {
      try {
        yaml.load(configFile);
      } catch (IOException e) {
        CustomDiscs.error("Error loading file: ", e);
      }
    }

    configVersion = getString("info.version", "1.9", "Don't change this value");
    setComment("info",
      "CustomDiscs Configuration",
      "CrabCraft: https://crabcraft.net");
    debug = getBoolean("global.debug", false);

    switch (configVersion) {
      case "1.0":
        migrateTo1_1();
      case "1.1":
        migrateTo1_2();
      case "1.2":
        migrateTo1_3();
      case "1.3":
        migrateTo1_4();
      case "1.4":
        migrateTo1_5();
      case "1.5":
        migrateTo1_6();
      case "1.6":
        migrateTo1_7();
      case "1.7":
        migrateTo1_8();
      case "1.8":
        migrateTo1_9();
    }

    for (Method method : this.getClass().getDeclaredMethods()) {
      if (Modifier.isPrivate(method.getModifiers()) &&
        method.getReturnType().equals(Void.TYPE) &&
        method.getName().endsWith("Settings")
      ) {
        try {
          method.invoke(this);
        } catch (Throwable t) {
          CustomDiscs.error("Failed to load configuration option from {}", t, method.getName());
        }
      }
    }

    save();
  }

  public void save() {
    try {
      yaml.save(configFile);
    } catch (IOException e) {
      CustomDiscs.error("Error saving file: ", e);
    }
  }

  private void setComment(String key, String... comment) {
    if (yaml.contains(key) && comment.length > 0) {
      yaml.setComment(key, String.join("\n", comment), CommentType.BLOCK);
    }
  }

  private void ensureDefault(String key, Object defaultValue, String... comment) {
    if (!yaml.contains(key))
      yaml.set(key, defaultValue);

    setComment(key, comment);
  }

  private boolean getBoolean(String key, boolean defaultValue, String... comment) {
    ensureDefault(key, defaultValue, comment);
    return yaml.getBoolean(key, defaultValue);
  }

  private int getInt(String key, int defaultValue, String... comment) {
    ensureDefault(key, defaultValue, comment);
    return yaml.getInt(key, defaultValue);
  }

  private double getDouble(String key, double defaultValue, String... comment) {
    ensureDefault(key, defaultValue, comment);
    return yaml.getDouble(key, defaultValue);
  }

  private String getString(String key, String defaultValue, String... comment) {
    ensureDefault(key, defaultValue, comment);
    return yaml.getString(key, defaultValue);
  }

  private List<String> getStringList(String key, List<String> defaultValue, String... comment) {
    ensureDefault(key, defaultValue, comment);
    return yaml.getStringList(key);
  }

  private String locale = Language.ENGLISH.getLabel();
  private boolean shouldCheckUpdates = true;
  private boolean debug = false;

  private void globalSettings() {
    locale = getString("global.locale", locale, "Language of the plugin",
      """
        Supported: %s
        Unknown languages will be replaced with %s""".formatted(Language.getAllSeparatedComma(), Language.ENGLISH.getLabel()
      )
    );
    if (!Language.isExists(locale)) locale = Language.ENGLISH.getLabel();
    shouldCheckUpdates = getBoolean("global.check-updates", shouldCheckUpdates);
    debug = getBoolean("global.debug", debug);
  }

  private List<String> remoteTabComplete = List.of("https://www.youtube.com/watch?v=", "https://soundcloud.com/");
  private int remoteCustomModelDataYoutube = 0;
  private String remoteFilterYoutube = "https?:\\/\\/(?:www\\.youtube\\.com\\/watch\\?v=|youtu\\.be\\/).+";
  private int remoteCustomModelDataSoundcloud = 0;
  private String remoteFilterSoundcloud = "https?:\\/\\/soundcloud\\.com\\/[^\\s]+";
  private int remoteCustomModelDataHttp = 0;
  private String remoteFilterHttp = "https?:\\/\\/.+";

  private void commandSettings() {
    remoteTabComplete = getStringList("command.create.remote.tabcomplete", remoteTabComplete);
    remoteCustomModelDataYoutube = getInt("command.create.remote.youtube.custom-model", remoteCustomModelDataYoutube);
    remoteFilterYoutube = getString("command.create.remote.youtube.filter", remoteFilterYoutube);
    remoteCustomModelDataSoundcloud = getInt("command.create.remote.soundcloud.custom-model", remoteCustomModelDataSoundcloud);
    remoteFilterSoundcloud = getString("command.create.remote.soundcloud.filter", remoteFilterSoundcloud);
    remoteCustomModelDataHttp = getInt("command.create.remote.http.custom-model", remoteCustomModelDataHttp);
    remoteFilterHttp = getString("command.create.remote.http.filter", remoteFilterHttp);

    setComment("command.create.remote.tabcomplete", """
      tabcomplete: suggestions shown while typing the remote command
      filter: regex used to apply custom-model-data to a remote disc""");
  }

  private float musicDiscVolume = 1f;
  private boolean allowHoppers = true;
  private int discRangeMin = 4;
  private int discRangeMax = 32;
  private int discRangeDefault = 24;

  private void discSettings() {
    musicDiscVolume = Float.parseFloat(getString("disc.volume", String.valueOf(musicDiscVolume),
      "The master volume of music discs from 0-1.", "You can set values like 0.5 for 50% volume."
    ));
    allowHoppers = getBoolean("disc.allow-hoppers", allowHoppers, "Please ensure that in the config/paper-world-defaults.yaml the value hopper.disable-move-event is false");
    discRangeMin = getInt("disc.range.min", discRangeMin,
      "The range slider in the create dialog: lowest, highest and starting value, in blocks.");
    discRangeMax = getInt("disc.range.max", discRangeMax);
    discRangeDefault = getInt("disc.range.default", discRangeDefault);
  }

  private float hornVolume = 1f;
  private int hornRange = 128;
  private int hornMaxLengthSeconds = 7;
  private int hornCooldownTicks = 140;
  private boolean hornCacheEnabled = true;
  private int hornCacheSize = 50;

  private void hornSettings() {
    hornVolume = Float.parseFloat(getString("horn.volume", String.valueOf(hornVolume),
      "The master volume of custom goat horns from 0-1.", "You can set values like 0.5 for 50% volume."
    ));
    // Range is fixed for every horn (no per-horn slider). Migrate the old
    // horn.range.{min,max,default} section to a single value if it is still present,
    // preserving the admin's horn.range.default as the new flat value.
    if (yaml.isConfigurationSection("horn.range")) {
      hornRange = yaml.getInt("horn.range.default", hornRange);
      removeValue("horn.range");
    }
    hornRange = getInt("horn.range", hornRange,
      "The range, in blocks, that every custom goat horn is heard at. Same for all horns.");
    hornMaxLengthSeconds = getInt("horn.max-length-seconds", hornMaxLengthSeconds,
      "Custom horn audio is capped to this length (seconds). Defaults to the vanilla 7s goat horn",
      "cooldown so a sound always finishes before the horn can be blown again.");
    hornCooldownTicks = getInt("horn.cooldown-ticks", hornCooldownTicks,
      "Cooldown applied after blowing a custom horn, in ticks (20 ticks = 1 second). Vanilla is 140.");
    hornCacheEnabled = getBoolean("horn.cache.enabled", hornCacheEnabled,
      "Cache the first horn.max-length-seconds of decoded audio on disk (in the horn-cache folder)",
      "so blows play instantly instead of waiting on yt-dlp/ffmpeg each time. Only the capped length",
      "is ever fetched and cached - a long source is never downloaded or stored in full.");
    hornCacheSize = getInt("horn.cache.size", hornCacheSize,
      "Max number of cached horn clips on disk (LRU). Each is roughly 0.7 MB at the default 7s length.");
  }

  private String ytDlpPath = "auto";
  private String ffmpegPath = "auto";
  private String ytDlpCookies = "";
  private String ytDlpProxy = "";

  private void providersSettings() {
    ytDlpPath = getString("providers.yt-dlp-path", ytDlpPath);
    setComment("providers.yt-dlp-path", """
      Path to the yt-dlp binary used to resolve stream URLs.
      'auto' downloads a pinned, SHA-256-verified build; a path uses an existing install;
      empty uses PATH.""");
    ffmpegPath = getString("providers.ffmpeg-path", ffmpegPath);
    setComment("providers.ffmpeg-path", """
      Path to the ffmpeg binary used to decode audio streams.
      'auto' downloads a pinned, SHA-256-verified static build; a path uses an existing install;
      empty uses PATH.""");
    ytDlpCookies = getString("providers.yt-dlp-cookies", ytDlpCookies);
    setComment("providers.yt-dlp-cookies", """
      Optional path to a Netscape-format cookies.txt exported from a logged-in browser
      (use a 'Get cookies.txt' browser extension). Lets yt-dlp access sign-in-required,
      age-restricted, or region-locked content and avoids YouTube 'confirm you're not a
      bot' errors. Leave empty to disable.""");
    ytDlpProxy = getString("providers.yt-dlp-proxy", ytDlpProxy);
    setComment("providers.yt-dlp-proxy", """
      Optional HTTP proxy (e.g. http://user:pass@host:port) routed through a residential IP.
      Bypasses YouTube's datacenter-IP bot blocking without needing cookies. Used for BOTH
      the yt-dlp resolve and the ffmpeg stream fetch (YouTube CDN URLs are IP-bound, so both
      must use the same proxy). Leave empty to disable.""");
  }

  private void setConfigVersion(String version) {
    yaml.set("info.version", version);
    configVersion = version;
  }

  private void removeValue(String key) {
    if (yaml.contains(key)) {
      yaml.remove(key);
      CustomDiscs.debug("Config successfully removed value {}", key);
      return;
    }
    CustomDiscs.debug("Config not found value {} to remove", key);
  }

  private void migrateValue(String key, String newKey) {
    if (yaml.contains(key)) {
      Object value = yaml.get(key);
      yaml.remove(key);
      yaml.set(newKey, value);
      CustomDiscs.debug("Config successfully migrated value {} to {}", key, newKey);
      return;
    }
    CustomDiscs.debug("Config not found value {} to migrate to {}", key, newKey);
  }

  private void migrateTo1_1() {
    CustomDiscs.debug("Config migrating from v1.0 to v1.1");
    migrateValue("music-disc-distance", "disc.distance");
    migrateValue("music-disc-volume", "disc.volume");
    migrateValue("max-download-size", "command.download.max-size");
    migrateValue("custom-model-data.enable", "command.create.custom-model-data.enable");
    migrateValue("custom-model-data.value", "command.create.custom-model-data.value");
    removeValue("custom-model-data");
    removeValue("providers.youtube.email");
    removeValue("providers.youtube.password");
    migrateValue("locale", "global.locale");
    migrateValue("debug", "global.debug");
    removeValue("cleaning-disc");
    setConfigVersion("1.1");
  }

  private void migrateTo1_2() {
    CustomDiscs.debug("Config migrating from v1.1 to v1.2");
    removeValue("providers.youtube.po-token.auto");
    setConfigVersion("1.2");
  }

  private void migrateTo1_3() {
    CustomDiscs.debug("Config migrating from v1.2 to v1.3");
    removeValue("command.create.custom-model-data");
    removeValue("command.createyt");
    removeValue("command.createsc");
    setConfigVersion("1.3");
  }

  private void migrateTo1_4() {
    CustomDiscs.debug("Config migrating from v1.3 to v1.4");
    removeValue("debug");
    setConfigVersion("1.4");
  }

  private void migrateTo1_5() {
    removeValue("command.create.remote.youtube.filter");
    removeValue("command.create.remote.soundcloud.filter");
    setConfigVersion("1.5");
  }

  private void migrateTo1_6() {
    CustomDiscs.debug("Config migrating from v1.5 to v1.6");
    String remoteUrl = yaml.getString("providers.youtube.remote-server.url", "");
    String remotePass = yaml.getString("providers.youtube.remote-server.password", "");
    if (remoteUrl != null && !remoteUrl.isBlank()) {
      yaml.set("lavalink.enabled", true);

      // Parse host and port from the URL (e.g. https://host:port)
      String host = remoteUrl
        .replaceFirst("^https?://", "")
        .replaceFirst("^wss?://", "")
        .replaceFirst("/.*$", "");
      boolean secure = remoteUrl.startsWith("https") || remoteUrl.startsWith("wss");
      int port = 443;
      if (host.contains(":")) {
        String[] parts = host.split(":", 2);
        host = parts[0];
        try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
      }
      yaml.set("lavalink.host", host);
      yaml.set("lavalink.port", port);
      yaml.set("lavalink.password", remotePass != null ? remotePass : "");
      yaml.set("lavalink.secure", secure);

      CustomDiscs.debug("Migrated remote-server URL '{}' to lavalink config (host={}, port={}, secure={})",
        remoteUrl, host, port, secure);
    }
    setConfigVersion("1.6");
  }

  private void migrateTo1_7() {
    CustomDiscs.debug("Config migrating from v1.6 to v1.7");
    removeValue("command.download");
    removeValue("command.create.local");
    setConfigVersion("1.7");
  }

  private void migrateTo1_8() {
    CustomDiscs.debug("Config migrating from v1.7 to v1.8");
    String old = yaml.getString("providers.youtube.yt-dlp-path", "");
    if (old != null && !old.isBlank()) yaml.set("providers.yt-dlp-path", old);
    removeValue("providers.youtube"); // removes the whole subtree (oauth2/po-token/remote-server/proxy/yt-dlp-path)
    removeValue("lavalink");
    setConfigVersion("1.8");
  }

  private void migrateTo1_9() {
    CustomDiscs.debug("Config migrating from v1.8 to v1.9");
    removeValue("disc.distance");
    removeValue("command.distance");
    setConfigVersion("1.9");
  }
}
