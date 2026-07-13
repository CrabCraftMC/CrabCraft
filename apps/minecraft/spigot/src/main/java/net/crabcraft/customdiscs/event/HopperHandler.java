package net.crabcraft.customdiscs.event;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.ItemStack;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.audio.AudioEngine;
import net.crabcraft.customdiscs.api.DiscEntry;
import net.crabcraft.customdiscs.api.event.CustomDiscEjectEvent;
import net.crabcraft.customdiscs.api.event.CustomDiscInsertEvent;
import net.crabcraft.customdiscs.util.LegacyUtil;
import net.crabcraft.customdiscs.util.PlayUtil;

public class HopperHandler implements Listener {
  @EventHandler(priority = EventPriority.NORMAL)
  public void onJukeboxInsertFromHopper(InventoryMoveItemEvent event) {
    if (!CustomDiscs.getPlugin().getCDConfig().isAllowHoppers()) return;
    if (event.getDestination().getLocation() == null) return;
    Block block = event.getDestination().getLocation().getBlock();
    if (!block.getType().equals(Material.JUKEBOX)) return;
    if (LegacyUtil.isJukeboxContainsDisc(block)) return;
    if (AudioEngine.getInstance().isPlaying(block)) return;

    if (!LegacyUtil.isCustomDisc(event.getItem())) return;
    DiscEntry discEntry = LegacyUtil.getDiscEntry(event.getItem());

    CustomDiscInsertEvent playEvent = new CustomDiscInsertEvent(block, null, discEntry);
    CustomDiscs.getPlugin().getServer().getPluginManager().callEvent(playEvent);
    if (!playEvent.isCancelled()) {
      event.setCancelled(true);

      // Start custom audio first so isPlaying() returns true for packet suppression
      PlayUtil.play(block, discEntry);

      ItemStack disc = event.getItem().clone();
      disc.setAmount(1);

      // Transfer the disc manually on next tick (safe for inventory events),
      // then start vanilla playback for comparator/hopper mechanics.
      CustomDiscs.getPlugin().getFoliaLib().getScheduler().runAtLocationLater(
        block.getLocation(), task -> {
          event.getSource().removeItem(disc);
          if (block.getState() instanceof Jukebox jukebox) {
            jukebox.setRecord(disc);
            jukebox.update();
          }
          // startPlaying() operates directly on the placed NMS entity — no update()
          if (block.getState() instanceof Jukebox fresh) {
            fresh.startPlaying();
          }
        }, 1
      );
    }
  }

  @EventHandler(priority = EventPriority.NORMAL)
  public void onJukeboxEjectToHopper(InventoryMoveItemEvent event) {
    if (event.getSource().getLocation() == null) return;
    Block block = event.getSource().getLocation().getBlock();
    if (!block.getType().equals(Material.JUKEBOX)) return;
    if (!event.getItem().hasItemMeta()) return;
    if (!LegacyUtil.isCustomDisc(event.getItem())) return;

    CustomDiscEjectEvent stopEvent = new CustomDiscEjectEvent(block, null, LegacyUtil.getDiscEntry(event.getItem()));
    CustomDiscs.getPlugin().getServer().getPluginManager().callEvent(stopEvent);
    if (stopEvent.isCancelled()) {
      event.setCancelled(true);
      return;
    }
    AudioEngine.getInstance().stopPlaying(block);
  }
}
