package crabcraft.net.crabUtilities.media.source

import crabcraft.net.crabUtilities.media.file.MediaConfig
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Classifies a submitted link using the administrator's provider rules. */
enum class MediaSourceKind(private val permissionSuffix: String) {
  YOUTUBE("youtube"), SOUNDCLOUD("soundcloud"), HTTP("http");

  fun permissionSuffix(): String = permissionSuffix
  fun itemModel(config: MediaConfig): Int = when (this) {
    YOUTUBE -> config.getRemoteCustomModelDataYoutube()
    SOUNDCLOUD -> config.getRemoteCustomModelDataSoundcloud()
    HTTP -> config.getRemoteCustomModelDataHttp()
  }

  companion object {
    @JvmStatic fun classify(source: String?, config: MediaConfig): MediaSourceKind? {
      if (source == null) return null
      if (matchesEntireSource(source, config.getRemoteFilterYoutube())) return YOUTUBE
      if (matchesEntireSource(source, config.getRemoteFilterSoundcloud())) return SOUNDCLOUD
      if (matchesEntireSource(source, config.getRemoteFilterHttp())) return HTTP
      return null
    }

    @JvmStatic fun matchesEntireSource(source: String?, expression: String?): Boolean {
      if (source == null || expression == null) return false
      return try { Pattern.compile(expression).matcher(source).matches() }
      catch (ignored: PatternSyntaxException) { false }
    }
  }
}
