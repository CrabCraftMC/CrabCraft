package net.crabcraft.customdiscs.util;

import lombok.Getter;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.file.CDConfig;

import java.util.function.Function;
import java.util.regex.Pattern;

@Getter
public enum RemoteServices {
  YOUTUBE("youtube", CDConfig::getRemoteFilterYoutube, CDConfig::getRemoteCustomModelDataYoutube),
  SOUNDCLOUD("soundcloud", CDConfig::getRemoteFilterSoundcloud, CDConfig::getRemoteCustomModelDataSoundcloud),
  HTTP("http", CDConfig::getRemoteFilterHttp, CDConfig::getRemoteCustomModelDataHttp);

  private final String id;
  private final Function<CDConfig, String> filterProvider;
  private final Function<CDConfig, Integer> modelDataProvider;

  RemoteServices(String id, Function<CDConfig, String> filterProvider, Function<CDConfig, Integer> modelDataProvider) {
    this.id = id;
    this.filterProvider = filterProvider;
    this.modelDataProvider = modelDataProvider;
  }

  public int getCustomModelData() {
    return modelDataProvider.apply(CustomDiscs.getPlugin().getCDConfig());
  }

  public static RemoteServices fromUrl(String url) {
    for (RemoteServices service : values()) {
      if (matches(url, service.filterProvider.apply(CustomDiscs.getPlugin().getCDConfig()))) {
        return service;
      }
    }
    return null;
  }

  static boolean matches(String url, String regex) {
    return Pattern.compile(regex).matcher(url).matches();
  }
}
