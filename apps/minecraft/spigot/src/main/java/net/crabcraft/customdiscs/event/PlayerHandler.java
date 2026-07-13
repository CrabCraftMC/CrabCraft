package net.crabcraft.customdiscs.event;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.audio.AudioEngine;
import net.crabcraft.customdiscs.api.DiscEntry;
import net.crabcraft.customdiscs.api.event.CustomDiscEjectEvent;
import net.crabcraft.customdiscs.api.event.CustomDiscInsertEvent;
import net.crabcraft.customdiscs.util.LegacyUtil;
import net.crabcraft.customdiscs.util.PlayUtil;

public class PlayerHandler implements Listener {
  private final CustomDiscs plugin = CustomDiscs.getPlugin();

  private static PlayerHandler instance;

  public synchronized static PlayerHandler getInstance() {
    if (instance == null) return instance = new PlayerHandler();
    return instance;
  }

  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onInsert(PlayerInteractEvent event) {
    Block block = event.getClickedBlock();

    if (!event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) return;
    if (event.getPlayer().isSneaking()) return;
    if (event.getClickedBlock() == null) return;
    if (event.getItem() == null) return;
    if (!event.getItem().hasItemMeta()) return;
    if (block == null) return;
    if (!block.getType().equals(Material.JUKEBOX)) return;
    if (LegacyUtil.isJukeboxContainsDisc(block)) return;

    if (!LegacyUtil.isCustomDisc(event.getItem())) return;

    CustomDiscs.debug("Jukebox insert by Player event");

    DiscEntry discEntry = LegacyUtil.getDiscEntry(event.getItem());

    CustomDiscInsertEvent playEvent = new CustomDiscInsertEvent(block, event.getPlayer(), discEntry);
    CustomDiscs.getPlugin().getServer().getPluginManager().callEvent(playEvent);
    if (!playEvent.isCancelled()) {
      // Cancel vanilla handling so we control the insertion ourselves.
      event.setCancelled(true);

      // Start custom audio FIRST so isPlaying() returns true, so the packet
      // listeners will suppress both the 1010 effect and BLOCK_ENTITY_DATA.
      PlayUtil.play(block, discEntry);

      // Manually place the disc and start vanilla playback. We need the
      // jukebox in a "playing" state for correct comparator output (1.21+
      // checks isPlaying()) and to block hopper extraction while playing.
      // The vanilla disc sound is suppressed by the packet listeners.
      ItemStack disc = event.getItem().clone();
      disc.setAmount(1);
      event.getItem().setAmount(event.getItem().getAmount() - 1);

      Jukebox jukebox = (Jukebox) block.getState();
      jukebox.setRecord(disc);
      jukebox.update();

      // startPlaying() operates directly on the placed NMS entity (calls
      // tryForcePlaySong). Do NOT call update() after, as it would apply the
      // snapshot's stale tick count and overwrite the timer reset.
      Jukebox fresh = (Jukebox) block.getState();
      fresh.startPlaying();
    }
  }

  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onEject(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    Block block = event.getClickedBlock();

    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    if (block == null) return;
    if (block.getType() != Material.JUKEBOX) return;
    if (!LegacyUtil.isJukeboxContainsDisc(block)) return;
    ItemStack item = event.getItem() != null ? event.getItem() : new ItemStack(Material.AIR);
    if (player.isSneaking() && item.getType() != Material.AIR) return;
    Jukebox jukebox = (Jukebox) block.getState();
    if (!LegacyUtil.isCustomDisc(jukebox.getRecord())) return;

    CustomDiscs.debug("Jukebox eject by Player event");

    CustomDiscEjectEvent stopEvent = new CustomDiscEjectEvent(block, event.getPlayer(), LegacyUtil.getDiscEntry(jukebox.getRecord()));
    CustomDiscs.getPlugin().getServer().getPluginManager().callEvent(stopEvent);

    if (stopEvent.isCancelled()) {
      event.setCancelled(true);
      return;
    }

    AudioEngine.getInstance().stopPlaying(block);
  }
}
