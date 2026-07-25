package crabcraft.net.crabUtilities.media.event;

import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.audio.AudioEngine;
import crabcraft.net.crabUtilities.media.item.MediaItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Jukebox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

/** Owns the complete lifecycle of audio attached to jukebox blocks. */
public final class JukeboxPlaybackListener implements Listener {

  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onPlayerUsesJukebox(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    Block block = event.getClickedBlock();
    if (block == null || block.getType() != Material.JUKEBOX) return;

    ItemStack held = event.getItem();
    if (!MediaItemCodec.jukeboxHasRecord(block)
      && held != null
      && !event.getPlayer().isSneaking()
      && MediaItemCodec.isDisc(held)) {
      insertByPlayer(event, block, held);
      return;
    }

    if (!MediaItemCodec.jukeboxHasRecord(block)) return;
    ItemStack nonNullHeld = held == null ? ItemStack.empty() : held;
    if (event.getPlayer().isSneaking() && !nonNullHeld.isEmpty()) return;
    Jukebox jukebox = (Jukebox) block.getState();
    if (MediaItemCodec.isDisc(jukebox.getRecord())) {
      AudioEngine.getInstance().stopPlaying(block);
    }
  }

  private static void insertByPlayer(PlayerInteractEvent event, Block block, ItemStack held) {
    event.setCancelled(true);
    start(block, MediaItemCodec.readDisc(held));

    ItemStack placed = held.clone();
    placed.setAmount(1);
    held.subtract();

    Jukebox jukebox = (Jukebox) block.getState();
    jukebox.setRecord(placed);
    jukebox.update();
    ((Jukebox) block.getState()).startPlaying();
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onHopperInsert(InventoryMoveItemEvent event) {
    if (!MediaFeature.get().getMediaConfig().isAllowHoppers()) return;
    Location destination = event.getDestination().getLocation();
    if (destination == null) return;
    Block block = destination.getBlock();
    if (block.getType() != Material.JUKEBOX
      || MediaItemCodec.jukeboxHasRecord(block)
      || AudioEngine.getInstance().isPlaying(block)
      || !MediaItemCodec.isDisc(event.getItem())) return;

    ItemStack expected = event.getItem().asOne();
    Bukkit.getScheduler().runTaskLater(
      MediaFeature.get().getJavaPlugin(),
      () -> beginIfTransferArrived(block, expected),
      1L);
  }

  private static void beginIfTransferArrived(Block block, ItemStack expected) {
    if (!(block.getState() instanceof Jukebox jukebox)) return;
    ItemStack actual = jukebox.getRecord();
    if (!transferCompleted(actual.isEmpty(), actual.isSimilar(expected))) return;
    if (AudioEngine.getInstance().isPlaying(block)) return;
    start(block, MediaItemCodec.readDisc(actual));
    jukebox.stopPlaying();
    jukebox.startPlaying();
  }

  static boolean transferCompleted(boolean placedDiscEmpty, boolean placedDiscMatches) {
    return !placedDiscEmpty && placedDiscMatches;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onHopperRemove(InventoryMoveItemEvent event) {
    Location source = event.getSource().getLocation();
    if (source == null || source.getBlock().getType() != Material.JUKEBOX) return;
    if (MediaItemCodec.isDisc(event.getItem())) {
      AudioEngine.getInstance().stopPlaying(source.getBlock());
    }
  }

  @EventHandler(priority = EventPriority.NORMAL)
  public void onBlockBreak(BlockBreakEvent event) {
    stopIfJukebox(event.getBlock());
  }

  @EventHandler(priority = EventPriority.NORMAL)
  public void onExplosion(EntityExplodeEvent event) {
    event.blockList().forEach(JukeboxPlaybackListener::stopIfJukebox);
  }

  private static void stopIfJukebox(Block block) {
    if (block.getType() == Material.JUKEBOX) AudioEngine.getInstance().stopPlaying(block);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onVanillaRecordExpiry(ItemSpawnEvent event) {
    ItemStack dropped = event.getEntity().getItemStack();
    if (!MediaItemCodec.isDisc(dropped)) return;

    Block spawnBlock = event.getLocation().getBlock();
    Block jukeboxBlock = spawnBlock.getType() == Material.JUKEBOX
      ? spawnBlock
      : spawnBlock.getRelative(BlockFace.DOWN);
    if (jukeboxBlock.getType() != Material.JUKEBOX
      || !AudioEngine.getInstance().isPlaying(jukeboxBlock)) return;

    event.setCancelled(true);
    Jukebox jukebox = (Jukebox) jukeboxBlock.getState();
    jukebox.setRecord(dropped);
    jukebox.update();
    ((Jukebox) jukeboxBlock.getState()).startPlaying();
  }

  private static void start(Block block, MediaItemCodec.DiscData disc) {
    AudioEngine.getInstance().play(block, disc.source(), disc.volume(), disc.range());
    Location notes = block.getLocation().add(0.5, 1.2, 0.5);
    new BukkitRunnable() {
      @Override
      public void run() {
        if (!AudioEngine.getInstance().isPlaying(block)) {
          cancel();
          return;
        }
        block.getWorld().spawnParticle(Particle.NOTE, notes, 1);
      }
    }.runTaskTimer(MediaFeature.get().getJavaPlugin(), 1L, 20L);
  }
}
