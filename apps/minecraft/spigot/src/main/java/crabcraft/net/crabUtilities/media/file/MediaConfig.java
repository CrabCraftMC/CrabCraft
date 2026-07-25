package crabcraft.net.crabUtilities.media.file;

import crabcraft.net.crabUtilities.CrabUtilities;

import java.util.List;

/**
 * Typed view of the {@code media} section in Crab Utilities' main
 * {@code modules/media.yml}.
 */
public final class MediaConfig {
  private static final String PREFIX = "media.";
  private final CrabUtilities plugin;

  public MediaConfig(CrabUtilities plugin) {
    this.plugin = plugin;
  }

  public boolean isDebug() {
    return plugin.getConfig().getBoolean(PREFIX + "debug", false);
  }

  public List<String> getRemoteTabComplete() {
    return plugin.getConfig().getStringList(PREFIX + "creation.remote.suggestions");
  }

  public int getRemoteCustomModelDataYoutube() {
    return plugin.getConfig().getInt(PREFIX + "creation.remote.youtube.custom-model", 0);
  }

  public String getRemoteFilterYoutube() {
    return plugin.getConfig().getString(
      PREFIX + "creation.remote.youtube.filter",
      "https?:\\/\\/(?:www\\.youtube\\.com\\/watch\\?v=|youtu\\.be\\/).+");
  }

  public int getRemoteCustomModelDataSoundcloud() {
    return plugin.getConfig().getInt(PREFIX + "creation.remote.soundcloud.custom-model", 0);
  }

  public String getRemoteFilterSoundcloud() {
    return plugin.getConfig().getString(
      PREFIX + "creation.remote.soundcloud.filter",
      "https?:\\/\\/soundcloud\\.com\\/[^\\s]+");
  }

  public int getRemoteCustomModelDataHttp() {
    return plugin.getConfig().getInt(PREFIX + "creation.remote.http.custom-model", 0);
  }

  public String getRemoteFilterHttp() {
    return plugin.getConfig().getString(
      PREFIX + "creation.remote.http.filter",
      "https?:\\/\\/.+");
  }

  public float getMusicDiscVolume() {
    return (float) plugin.getConfig().getDouble(PREFIX + "discs.volume", 1.0);
  }

  public boolean isAllowHoppers() {
    return plugin.getConfig().getBoolean(PREFIX + "discs.allow-hoppers", true);
  }

  public int getDiscRangeMin() {
    return plugin.getConfig().getInt(PREFIX + "discs.range.min", 4);
  }

  public int getDiscRangeMax() {
    return plugin.getConfig().getInt(PREFIX + "discs.range.max", 32);
  }

  public int getDiscRangeDefault() {
    return plugin.getConfig().getInt(PREFIX + "discs.range.default", 24);
  }

  public float getHornVolume() {
    return (float) plugin.getConfig().getDouble(PREFIX + "horns.volume", 1.0);
  }

  public int getHornRange() {
    return plugin.getConfig().getInt(PREFIX + "horns.range", 128);
  }

  public int getHornMaxLengthSeconds() {
    return plugin.getConfig().getInt(PREFIX + "horns.max-length-seconds", 7);
  }

  public int getHornCooldownTicks() {
    return plugin.getConfig().getInt(PREFIX + "horns.cooldown-ticks", 140);
  }

  public boolean isHornCacheEnabled() {
    return plugin.getConfig().getBoolean(PREFIX + "horns.cache.enabled", true);
  }

  public int getHornCacheSize() {
    return plugin.getConfig().getInt(PREFIX + "horns.cache.size", 50);
  }

  public String getYtDlpPath() {
    return plugin.getConfig().getString(PREFIX + "providers.yt-dlp-path", "auto");
  }

  public String getFfmpegPath() {
    return plugin.getConfig().getString(PREFIX + "providers.ffmpeg-path", "auto");
  }

  public String getYtDlpCookies() {
    return plugin.getConfig().getString(PREFIX + "providers.yt-dlp-cookies", "");
  }

  public String getYtDlpProxy() {
    return plugin.getConfig().getString(PREFIX + "providers.yt-dlp-proxy", "");
  }
}
