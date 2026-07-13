package net.crabcraft.customdiscs.event;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Jukebox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.audio.AudioEngine;
import net.crabcraft.customdiscs.util.LegacyUtil;

public class JukeboxHandler implements Listener {
  @EventHandler(priority = EventPriority.NORMAL)
  public void onJukeboxBreak(BlockBreakEvent event) {
    Block block = event.getBlock();
    if (block.getType() == Material.JUKEBOX) {
      AudioEngine.getInstance().stopPlaying(block);
    }
  }

  @EventHandler(priority = EventPriority.NORMAL)
  public void onJukeboxExplode(EntityExplodeEvent event) {
    for (Block explodedBlock : event.blockList()) {
      if (explodedBlock.getType() == Material.JUKEBOX) {
        AudioEngine.getInstance().stopPlaying(explodedBlock);
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDiscAutoEject(ItemSpawnEvent event) {
    ItemStack item = event.getEntity().getItemStack();
    if (!item.hasItemMeta()) return;
    if (!LegacyUtil.isCustomDisc(item)) return;

    // Jukebox drops items at or above its position
    Location loc = event.getEntity().getLocation();
    Block block = loc.getBlock();
    Block jukeboxBlock = null;
    if (block.getType() == Material.JUKEBOX) jukeboxBlock = block;
    else {
      Block below = block.getRelative(BlockFace.DOWN);
      if (below.getType() == Material.JUKEBOX) jukeboxBlock = below;
    }
    if (jukeboxBlock == null) return;
    if (!AudioEngine.getInstance().isPlaying(jukeboxBlock)) return;

    CustomDiscs.debug("Auto-eject caught for custom disc at {}", jukeboxBlock.getLocation());

    // Cancel the drop and re-insert the disc
    event.setCancelled(true);

    Jukebox jukebox = (Jukebox) jukeboxBlock.getState();
    jukebox.setRecord(item);
    jukebox.update();

    // Restart vanilla playback to reset the auto-eject timer.
    // Packet listeners suppress both the 1010 effect and BLOCK_ENTITY_DATA.
    // startPlaying() operates directly on the placed NMS entity — no update()
    Jukebox fresh = (Jukebox) jukeboxBlock.getState();
    fresh.startPlaying();
  }

}
