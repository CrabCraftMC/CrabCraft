package crabcraft.net.crabUtilities.media.file

import crabcraft.net.crabUtilities.CrabUtilities

/** Typed view of the media section in modules/media.yml. */
class MediaConfig(private val plugin: CrabUtilities) {
    fun isDebug() = plugin.config.getBoolean(PREFIX + "debug", false)

    fun getRemoteTabComplete() = plugin.config.getStringList(PREFIX + "creation.remote.suggestions")

    fun getRemoteCustomModelDataYoutube() = plugin.config.getInt(PREFIX + "creation.remote.youtube.custom-model", 0)

    fun getRemoteFilterYoutube() =
        plugin.config.getString(
            PREFIX + "creation.remote.youtube.filter",
            "https?:\\/\\/(?:www\\.youtube\\.com\\/watch\\?v=|youtu\\.be\\/).+",
        )!!

    fun getRemoteCustomModelDataSoundcloud() =
        plugin.config.getInt(PREFIX + "creation.remote.soundcloud.custom-model", 0)

    fun getRemoteFilterSoundcloud() =
        plugin.config.getString(
            PREFIX + "creation.remote.soundcloud.filter",
            "https?:\\/\\/soundcloud\\.com\\/[^\\s]+",
        )!!

    fun getRemoteCustomModelDataHttp() = plugin.config.getInt(PREFIX + "creation.remote.http.custom-model", 0)

    fun getRemoteFilterHttp() =
        plugin.config.getString(
            PREFIX + "creation.remote.http.filter",
            "https?:\\/\\/.+",
        )!!

    fun getMusicDiscVolume() = (plugin.config.getDouble(PREFIX + "discs.volume", 1.0)).toFloat()

    fun isAllowHoppers() = plugin.config.getBoolean(PREFIX + "discs.allow-hoppers", true)

    fun getDiscRangeMin() = plugin.config.getInt(PREFIX + "discs.range.min", 4)

    fun getDiscRangeMax() = plugin.config.getInt(PREFIX + "discs.range.max", 32)

    fun getDiscRangeDefault() = plugin.config.getInt(PREFIX + "discs.range.default", 24)

    fun getHornVolume() = (plugin.config.getDouble(PREFIX + "horns.volume", 1.0)).toFloat()

    fun getHornRange() = plugin.config.getInt(PREFIX + "horns.range", 128)

    fun getHornMaxLengthSeconds() = plugin.config.getInt(PREFIX + "horns.max-length-seconds", 7)

    fun getHornCooldownTicks() = plugin.config.getInt(PREFIX + "horns.cooldown-ticks", 140)

    fun isHornCacheEnabled() = plugin.config.getBoolean(PREFIX + "horns.cache.enabled", true)

    fun getHornCacheSize() = plugin.config.getInt(PREFIX + "horns.cache.size", 50)

    fun getYtDlpPath() = plugin.config.getString(PREFIX + "providers.yt-dlp-path", "auto")!!

    fun getFfmpegPath() = plugin.config.getString(PREFIX + "providers.ffmpeg-path", "auto")!!

    fun getYtDlpCookies() = plugin.config.getString(PREFIX + "providers.yt-dlp-cookies", "")!!

    fun getYtDlpProxy() = plugin.config.getString(PREFIX + "providers.yt-dlp-proxy", "")!!

    companion object {
        private const val PREFIX = "media."
    }
}
