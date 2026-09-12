package crabcraft.net.crabUtilities.media

import de.maxhenkel.voicechat.api.VoicechatServerApi
import javax.imageio.ImageIO
import java.io.IOException

/** Stores the voice-chat API and registers CrabCraft's audio volume controls. */
class VoiceMediaRegistry private constructor() {
  @Volatile private var serverApi: VoicechatServerApi? = null

  fun serverApi(): VoicechatServerApi =
    serverApi ?: throw IllegalStateException("Simple Voice Chat is not ready")

  fun attach(api: VoicechatServerApi, includeLofi: Boolean) {
    serverApi = api
    addVolumeControl(api, MUSIC_DISC_CATEGORY, "Music Discs", "media/music_disc_category.png")
    addVolumeControl(api, GOAT_HORN_CATEGORY, "Goat Horns", "media/goat_horn_category.png")
    addVolumeControl(api, CALL_CATEGORY, "Call Ringtones", "media/music_disc_category.png")
    if (includeLofi) addVolumeControl(api, LOFI_CATEGORY, "Lofi 24/7 CrabFM", "media/music_disc_category.png")
  }

  fun detach() { serverApi = null }

  companion object {
    const val MUSIC_DISC_CATEGORY = "music_discs"
    const val GOAT_HORN_CATEGORY = "goat_horns"
    const val LOFI_CATEGORY = "crabcraft_lofi"
    const val CALL_CATEGORY = "crabcraft_calls"
    private val INSTANCE = VoiceMediaRegistry()

    @JvmStatic fun getInstance(): VoiceMediaRegistry = INSTANCE

    private fun addVolumeControl(api: VoicechatServerApi, identifier: String, displayName: String, iconPath: String) {
      api.registerVolumeCategory(api.volumeCategoryBuilder()
        .setId(identifier).setName(displayName).setIcon(loadIcon(iconPath)).build())
    }

    private fun loadIcon(resourcePath: String): Array<IntArray>? {
      try {
        VoiceMediaRegistry::class.java.classLoader.getResourceAsStream(resourcePath).use { stream ->
          if (stream == null) return null
          val image = ImageIO.read(stream)
          if (image == null || image.width != 16 || image.height != 16) return null
          val rowMajor = image.getRGB(0, 0, 16, 16, null, 0, 16)
          val columns = Array(16) { IntArray(16) }
          for (index in rowMajor.indices) columns[index % 16][index / 16] = rowMajor[index]
          return columns
        }
      } catch (error: IOException) {
        MediaFeature.error("Could not read voice-chat icon {}", error, resourcePath)
        return null
      }
    }
  }
}
