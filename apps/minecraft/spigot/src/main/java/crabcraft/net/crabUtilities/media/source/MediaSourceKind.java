package crabcraft.net.crabUtilities.media.source;

import crabcraft.net.crabUtilities.media.file.MediaConfig;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Classifies a submitted link using the administrator's provider rules. */
public enum MediaSourceKind {
  YOUTUBE("youtube"),
  SOUNDCLOUD("soundcloud"),
  HTTP("http");

  private final String permissionSuffix;

  MediaSourceKind(String permissionSuffix) {
    this.permissionSuffix = permissionSuffix;
  }

  public String permissionSuffix() {
    return permissionSuffix;
  }

  public int itemModel(MediaConfig config) {
    return switch (this) {
      case YOUTUBE -> config.getRemoteCustomModelDataYoutube();
      case SOUNDCLOUD -> config.getRemoteCustomModelDataSoundcloud();
      case HTTP -> config.getRemoteCustomModelDataHttp();
    };
  }

  public static MediaSourceKind classify(String source, MediaConfig config) {
    if (source == null) return null;
    if (matchesEntireSource(source, config.getRemoteFilterYoutube())) return YOUTUBE;
    if (matchesEntireSource(source, config.getRemoteFilterSoundcloud())) return SOUNDCLOUD;
    if (matchesEntireSource(source, config.getRemoteFilterHttp())) return HTTP;
    return null;
  }

  public static boolean matchesEntireSource(String source, String expression) {
    if (source == null || expression == null) return false;
    try {
      return Pattern.compile(expression).matcher(source).matches();
    } catch (PatternSyntaxException ignored) {
      return false;
    }
  }
}
