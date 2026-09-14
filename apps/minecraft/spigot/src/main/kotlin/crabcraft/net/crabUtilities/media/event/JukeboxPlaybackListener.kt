package crabcraft.net.crabUtilities.media.event

import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.audio.AudioEngine
import crabcraft.net.crabUtilities.media.item.MediaItemCodec
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Jukebox
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitRunnable

/** Owns the complete lifecycle of audio attached to jukebox blocks. */
class JukeboxPlaybackListener : Listener {
  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  fun onPlayerUsesJukebox(event: PlayerInteractEvent) {
    if (event.action != Action.RIGHT_CLICK_BLOCK) return
    val block = event.clickedBlock
    if (block == null || block.type != Material.JUKEBOX) return
    val held = event.item
    if (!MediaItemCodec.jukeboxHasRecord(block) && held != null
      && !event.player.isSneaking && MediaItemCodec.isDisc(held)) {
      insertByPlayer(event, block, held)
      return
    }
    if (!MediaItemCodec.jukeboxHasRecord(block)) return
    val nonNullHeld = held ?: ItemStack.empty()
    if (event.player.isSneaking && !nonNullHeld.isEmpty) return
    val jukebox = block.state as Jukebox
    if (MediaItemCodec.isDisc(jukebox.record)) AudioEngine.getInstance().stopPlaying(block)
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onHopperInsert(event: InventoryMoveItemEvent) {
    if (!MediaFeature.get().getMediaConfig().isAllowHoppers()) return
    val destination = event.destination.location ?: return
    val block = destination.block
    if (block.type != Material.JUKEBOX || MediaItemCodec.jukeboxHasRecord(block)
      || AudioEngine.getInstance().isPlaying(block) || !MediaItemCodec.isDisc(event.item)) return
    val expected = event.item.asOne()
    Bukkit.getScheduler().runTaskLater(MediaFeature.get().getJavaPlugin(),
      Runnable { beginIfTransferArrived(block, expected) }, 1L)
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onHopperRemove(event: InventoryMoveItemEvent) {
    val source = event.source.location
    if (source == null || source.block.type != Material.JUKEBOX) return
    if (MediaItemCodec.isDisc(event.item)) AudioEngine.getInstance().stopPlaying(source.block)
  }

  @EventHandler(priority = EventPriority.NORMAL)
  fun onBlockBreak(event: BlockBreakEvent) { stopIfJukebox(event.block) }

  @EventHandler(priority = EventPriority.NORMAL)
  fun onExplosion(event: EntityExplodeEvent) { event.blockList().forEach(::stopIfJukebox) }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  fun onVanillaRecordExpiry(event: ItemSpawnEvent) {
    val dropped = event.entity.itemStack
    if (!MediaItemCodec.isDisc(dropped)) return
    val spawnBlock = event.location.block
    val jukeboxBlock = if (spawnBlock.type == Material.JUKEBOX) spawnBlock else spawnBlock.getRelative(BlockFace.DOWN)
    if (jukeboxBlock.type != Material.JUKEBOX || !AudioEngine.getInstance().isPlaying(jukeboxBlock)) return
    event.isCancelled = true
    val jukebox = jukeboxBlock.state as Jukebox
    jukebox.setRecord(dropped)
    jukebox.update()
    (jukeboxBlock.state as Jukebox).startPlaying()
  }

  companion object {
    private fun insertByPlayer(event: PlayerInteractEvent, block: Block, held: ItemStack) {
      event.isCancelled = true
      start(block, MediaItemCodec.readDisc(held))
      val placed = held.clone()
      placed.amount = 1
      held.subtract()
      val jukebox = block.state as Jukebox
      jukebox.setRecord(placed)
      jukebox.update()
      (block.state as Jukebox).startPlaying()
    }

    private fun beginIfTransferArrived(block: Block, expected: ItemStack) {
      val jukebox = block.state as? Jukebox ?: return
      val actual = jukebox.record
      if (!transferCompleted(actual.isEmpty, actual.isSimilar(expected))) return
      if (AudioEngine.getInstance().isPlaying(block)) return
      start(block, MediaItemCodec.readDisc(actual))
      jukebox.stopPlaying()
      jukebox.startPlaying()
    }

    @JvmStatic fun transferCompleted(placedDiscEmpty: Boolean, placedDiscMatches: Boolean): Boolean =
      !placedDiscEmpty && placedDiscMatches

    private fun stopIfJukebox(block: Block) {
      if (block.type == Material.JUKEBOX) AudioEngine.getInstance().stopPlaying(block)
    }

    private fun start(block: Block, disc: MediaItemCodec.DiscData) {
      AudioEngine.getInstance().play(block, disc.source(), disc.volume(), disc.range())
      val notes = block.location.add(0.5, 1.2, 0.5)
      object : BukkitRunnable() {
        override fun run() {
          if (!AudioEngine.getInstance().isPlaying(block)) {
            cancel()
            return
          }
          block.world.spawnParticle(Particle.NOTE, notes, 1)
        }
      }.runTaskTimer(MediaFeature.get().getJavaPlugin(), 1L, 20L)
    }
  }
}
