package crabcraft.net.crabUtilities.media.event

import crabcraft.net.crabUtilities.media.MediaFeature
import crabcraft.net.crabUtilities.media.audio.AudioEngine
import crabcraft.net.crabUtilities.media.item.MediaItemCodec
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/** Starts entity-following playback when a player uses an encoded goat horn. */
class HornPlaybackListener : Listener {
  @EventHandler(priority = EventPriority.NORMAL)
  fun onHornUse(event: PlayerInteractEvent) {
    if (event.hand != EquipmentSlot.HAND) return
    val action = event.action
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return
    val item = event.item
    if (!MediaItemCodec.isHorn(item)) return
    val player = event.player
    if (wouldUseClickedBlock(action, event.clickedBlock, player)) return
    if (player.hasCooldown(Material.GOAT_HORN)) return
    val cooldown = MediaFeature.get().getMediaConfig().getHornCooldownTicks()
    Bukkit.getScheduler().runTaskLater(MediaFeature.get().getJavaPlugin(),
      Runnable { player.setCooldown(Material.GOAT_HORN, cooldown) }, 1L)
    val horn = MediaItemCodec.readHorn(item)
    AudioEngine.getInstance().playHorn(player, horn.source(), horn.volume())
  }

  companion object {
    private fun wouldUseClickedBlock(action: Action, clicked: Block?, player: Player): Boolean =
      action == Action.RIGHT_CLICK_BLOCK && clicked != null && clicked.type.isInteractable && !player.isSneaking
  }
}
