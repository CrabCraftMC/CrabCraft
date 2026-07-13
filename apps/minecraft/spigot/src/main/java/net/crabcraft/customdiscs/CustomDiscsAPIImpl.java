package net.crabcraft.customdiscs;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import net.crabcraft.customdiscs.audio.AudioEngine;
import net.crabcraft.customdiscs.api.AudioManager;
import net.crabcraft.customdiscs.api.CustomDiscsAPI;
import net.crabcraft.customdiscs.util.LegacyUtil;

public class CustomDiscsAPIImpl implements CustomDiscsAPI {
  @Override
  public @NotNull AudioManager getAudioManager() {
    return AudioEngine.getInstance();
  }

  @Override
  public boolean isCustomDisc(@NotNull ItemStack item) {
    return LegacyUtil.isCustomDisc(item);
  }
}
