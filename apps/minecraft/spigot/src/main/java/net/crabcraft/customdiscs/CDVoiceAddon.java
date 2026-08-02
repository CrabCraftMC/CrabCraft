package net.crabcraft.customdiscs;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Enumeration;

/** Shared Simple Voice Chat state for the merged media features. */
public final class CDVoiceAddon {
  public static final String MUSIC_DISC_CATEGORY = "music_discs";
  public static final String GOAT_HORN_CATEGORY = "goat_horns";
  public static final String LOFI_CATEGORY = "crabcraft_lofi";
  public static final String CALL_CATEGORY = "crabcraft_calls";

  private static final CDVoiceAddon INSTANCE = new CDVoiceAddon();
  private volatile VoicechatServerApi voicechatApi;

  private CDVoiceAddon() {}

  public static CDVoiceAddon getInstance() { return INSTANCE; }
  public VoicechatServerApi getVoicechatApi() { return voicechatApi; }

  public void register(VoicechatServerApi api, boolean lofiEnabled) {
    this.voicechatApi = api;
    registerCategory(api, MUSIC_DISC_CATEGORY, "Music Discs", "customdiscs/music_disc_category.png");
    registerCategory(api, GOAT_HORN_CATEGORY, "Goat Horns", "customdiscs/goat_horn_category.png");
    registerCategory(api, CALL_CATEGORY, "Call Ringtones", "customdiscs/music_disc_category.png");
    if (lofiEnabled) {
      registerCategory(api, LOFI_CATEGORY, "Lofi 24/7 CrabFM", "customdiscs/music_disc_category.png");
    }
  }

  public void clear() { this.voicechatApi = null; }

  private void registerCategory(VoicechatServerApi api, String id, String name, String iconResource) {
    VolumeCategory category = api.volumeCategoryBuilder()
      .setId(id)
      .setName(name)
      .setIcon(getIcon(iconResource))
      .build();
    api.registerVolumeCategory(category);
  }

  private int[][] getIcon(String resourceName) {
    try {
      Enumeration<URL> resources = getClass().getClassLoader().getResources(resourceName);
      while (resources.hasMoreElements()) {
        BufferedImage bufferedImage = ImageIO.read(resources.nextElement().openStream());
        if (bufferedImage.getWidth() != 16 || bufferedImage.getHeight() != 16) continue;
        int[][] image = new int[16][16];
        for (int x = 0; x < bufferedImage.getWidth(); x++) {
          for (int y = 0; y < bufferedImage.getHeight(); y++) {
            image[x][y] = bufferedImage.getRGB(x, y);
          }
        }
        return image;
      }
    } catch (Throwable e) {
      CustomDiscs.error("Error loading voice-chat icon '{}': ", e, resourceName);
    }
    return null;
  }
}
