package crabcraft.net.crabUtilities.media.file

import crabcraft.net.crabUtilities.CrabUtilities


/**
 * Typed view of the {@code media} section in Crab Utilities' main
 * {@code modules/media.yml}.
 */
class MediaConfig(private val plugin: CrabUtilities) {
  companion object { private const val PREFIX = "media." }

  fun isDebug(): Boolean {
    return plugin.getConfig().getBoolean(PREFIX + "debug", false)
  }

  fun getRemoteTabComplete(): List<String> {
    return plugin.getConfig().getStringList(PREFIX + "creation.remote.suggestions")
  }

  fun getRemoteCustomModelDataYoutube(): Int {
    return plugin.getConfig().getInt(PREFIX + "creation.remote.youtube.custom-model", 0)
  }

  fun getRemoteFilterYoutube(): String {
    return plugin.getConfig().getString(
      PREFIX + "creation.remote.youtube.filter",
      "https?:\\/\\/(?:www\\.youtube\\.com\\/watch\\?v=|youtu\\.be\\/).+")!!
  }

  fun getRemoteCustomModelDataSoundcloud(): Int {
    return plugin.getConfig().getInt(PREFIX + "creation.remote.soundcloud.custom-model", 0)
  }

  fun getRemoteFilterSoundcloud(): String {
    return plugin.getConfig().getString(
      PREFIX + "creation.remote.soundcloud.filter",
      "https?:\\/\\/soundcloud\\.com\\/[^\\s]+")!!
  }

  fun getRemoteCustomModelDataHttp(): Int {
    return plugin.getConfig().getInt(PREFIX + "creation.remote.http.custom-model", 0)
  }

  fun getRemoteFilterHttp(): String {
    return plugin.getConfig().getString(
      PREFIX + "creation.remote.http.filter",
      "https?:\\/\\/.+")!!
  }

  fun getMusicDiscVolume(): Float {
    return plugin.getConfig().getDouble(PREFIX + "discs.volume", 1.0).toFloat()
  }

  fun isAllowHoppers(): Boolean {
    return plugin.getConfig().getBoolean(PREFIX + "discs.allow-hoppers", true)
  }

  fun getDiscRangeMin(): Int {
    return plugin.getConfig().getInt(PREFIX + "discs.range.min", 4)
  }

  fun getDiscRangeMax(): Int {
    return plugin.getConfig().getInt(PREFIX + "discs.range.max", 32)
  }

  fun getDiscRangeDefault(): Int {
    return plugin.getConfig().getInt(PREFIX + "discs.range.default", 24)
  }

  fun getHornVolume(): Float {
    return plugin.getConfig().getDouble(PREFIX + "horns.volume", 1.0).toFloat()
  }

  fun getHornRange(): Int {
    return plugin.getConfig().getInt(PREFIX + "horns.range", 128)
  }

  fun getHornMaxLengthSeconds(): Int {
    return plugin.getConfig().getInt(PREFIX + "horns.max-length-seconds", 7)
  }

  fun getHornCooldownTicks(): Int {
    return plugin.getConfig().getInt(PREFIX + "horns.cooldown-ticks", 140)
  }

  fun isHornCacheEnabled(): Boolean {
    return plugin.getConfig().getBoolean(PREFIX + "horns.cache.enabled", true)
  }

  fun getHornCacheSize(): Int {
    return plugin.getConfig().getInt(PREFIX + "horns.cache.size", 50)
  }

  fun getYtDlpPath(): String {
    return plugin.getConfig().getString(PREFIX + "providers.yt-dlp-path", "auto")!!
  }

  fun getFfmpegPath(): String {
    return plugin.getConfig().getString(PREFIX + "providers.ffmpeg-path", "auto")!!
  }

  fun getYtDlpCookies(): String {
    return plugin.getConfig().getString(PREFIX + "providers.yt-dlp-cookies", "")!!
  }

  fun getYtDlpProxy(): String {
    return plugin.getConfig().getString(PREFIX + "providers.yt-dlp-proxy", "")!!
  }
}
