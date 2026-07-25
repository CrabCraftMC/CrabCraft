package crabcraft.net.crabUtilities.media;

import de.maxhenkel.voicechat.api.VoicechatServerApi;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/** Stores the voice-chat API and registers CrabCraft's audio volume controls. */
public final class VoiceMediaRegistry {
  public static final String MUSIC_DISC_CATEGORY = "music_discs";
  public static final String GOAT_HORN_CATEGORY = "goat_horns";
  public static final String LOFI_CATEGORY = "crabcraft_lofi";

  private static final VoiceMediaRegistry INSTANCE = new VoiceMediaRegistry();
  private volatile VoicechatServerApi serverApi;

  private VoiceMediaRegistry() {}

  public static VoiceMediaRegistry getInstance() {
    return INSTANCE;
  }

  public VoicechatServerApi serverApi() {
    VoicechatServerApi api = serverApi;
    if (api == null) throw new IllegalStateException("Simple Voice Chat is not ready");
    return api;
  }

  public void attach(VoicechatServerApi api, boolean includeLofi) {
    serverApi = api;
    addVolumeControl(api, MUSIC_DISC_CATEGORY, "Music Discs", "media/music_disc_category.png");
    addVolumeControl(api, GOAT_HORN_CATEGORY, "Goat Horns", "media/goat_horn_category.png");
    if (includeLofi) {
      addVolumeControl(api, LOFI_CATEGORY, "Lofi 24/7 CrabFM", "media/music_disc_category.png");
    }
  }

  public void detach() {
    serverApi = null;
  }

  private static void addVolumeControl(
    VoicechatServerApi api,
    String identifier,
    String displayName,
    String iconPath
  ) {
    api.registerVolumeCategory(api.volumeCategoryBuilder()
      .setId(identifier)
      .setName(displayName)
      .setIcon(loadIcon(iconPath))
      .build());
  }

  private static int[][] loadIcon(String resourcePath) {
    try (InputStream stream = VoiceMediaRegistry.class.getClassLoader()
      .getResourceAsStream(resourcePath)) {
      if (stream == null) return null;
      BufferedImage image = ImageIO.read(stream);
      if (image == null || image.getWidth() != 16 || image.getHeight() != 16) return null;

      int[] rowMajor = image.getRGB(0, 0, 16, 16, null, 0, 16);
      int[][] columns = new int[16][16];
      for (int index = 0; index < rowMajor.length; index++) {
        columns[index % 16][index / 16] = rowMajor[index];
      }
      return columns;
    } catch (IOException error) {
      MediaFeature.error("Could not read voice-chat icon {}", error, resourcePath);
      return null;
    }
  }
}
