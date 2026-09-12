package crabcraft.net.crabUtilities.media.util

import crabcraft.net.crabUtilities.media.source.MediaSourceKind
import java.util.function.Predicate

/** Shared sink-time checks for remote disc and horn creation and playback. */
class RemoteMediaSecurity private constructor() {
  companion object {
    const val MIN_VOLUME = 0f
    const val MAX_VOLUME = 2f

    @JvmStatic fun canCreate(hasPermission: Predicate<String>, sourceKind: MediaSourceKind?): Boolean =
      sourceKind != null && hasPermission.test("crabutilities.media.create")
        && hasPermission.test("crabutilities.media.create.remote")
        && hasPermission.test("crabutilities.media.create.remote." + sourceKind.permissionSuffix())

    @JvmStatic fun isValidVolume(volume: Float): Boolean =
      volume.isFinite() && volume >= MIN_VOLUME && volume <= MAX_VOLUME

    @JvmStatic fun isValidDiscSettings(volume: Float, distance: Int, rangeMin: Int, rangeMax: Int): Boolean =
      isValidVolume(volume) && rangeMin >= 0 && rangeMax >= rangeMin
        && distance >= rangeMin && distance <= rangeMax

    @JvmStatic fun playbackDistance(storedDistance: Int, configuredDefault: Int, rangeMin: Int, rangeMax: Int): Int {
      val selected = if (storedDistance > 0) storedDistance else configuredDefault
      return Math.max(rangeMin, Math.min(rangeMax, selected))
    }
  }
}
