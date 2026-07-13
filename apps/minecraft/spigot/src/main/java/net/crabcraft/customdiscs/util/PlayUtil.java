package net.crabcraft.customdiscs.util;

import org.bukkit.block.Block;
import net.crabcraft.customdiscs.audio.AudioEngine;
import net.crabcraft.customdiscs.ParticleManager;
import net.crabcraft.customdiscs.api.DiscEntry;

public class PlayUtil {
  public static void play(Block block, DiscEntry disc) {
    ParticleManager.start(block);
    AudioEngine.getInstance().play(block, disc.getIdentifier(), disc.getVolume(), disc.getDistance());
  }
}
