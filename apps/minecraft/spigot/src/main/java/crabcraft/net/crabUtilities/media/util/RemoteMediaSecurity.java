package crabcraft.net.crabUtilities.media.util;

import crabcraft.net.crabUtilities.media.source.MediaSourceKind;

import java.util.function.Predicate;

/** Shared sink-time checks for remote disc and horn creation and playback. */
public final class RemoteMediaSecurity {
  public static final float MIN_VOLUME = 0f;
  public static final float MAX_VOLUME = 2f;

  private RemoteMediaSecurity() {}

  public static boolean canCreate(Predicate<String> hasPermission, MediaSourceKind sourceKind) {
    return sourceKind != null
      && hasPermission.test("crabutilities.media.create")
      && hasPermission.test("crabutilities.media.create.remote")
      && hasPermission.test("crabutilities.media.create.remote." + sourceKind.permissionSuffix());
  }

  public static boolean isValidVolume(float volume) {
    return Float.isFinite(volume) && volume >= MIN_VOLUME && volume <= MAX_VOLUME;
  }

  public static boolean isValidDiscSettings(float volume, int distance, int rangeMin, int rangeMax) {
    return isValidVolume(volume)
      && rangeMin >= 0
      && rangeMax >= rangeMin
      && distance >= rangeMin
      && distance <= rangeMax;
  }

  public static int playbackDistance(int storedDistance, int configuredDefault,
                                     int rangeMin, int rangeMax) {
    int selected = storedDistance > 0 ? storedDistance : configuredDefault;
    return Math.max(rangeMin, Math.min(rangeMax, selected));
  }
}
