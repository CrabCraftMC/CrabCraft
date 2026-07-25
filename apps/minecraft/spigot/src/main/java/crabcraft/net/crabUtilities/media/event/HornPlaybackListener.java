package crabcraft.net.crabUtilities.media.event;

import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.audio.AudioEngine;
import crabcraft.net.crabUtilities.media.item.MediaItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Starts entity-following playback when a player uses an encoded goat horn. */
public final class HornPlaybackListener implements Listener {
  @EventHandler(priority = EventPriority.NORMAL)
  public void onHornUse(PlayerInteractEvent event) {
    if (event.getHand() != EquipmentSlot.HAND) return;
    Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

    ItemStack item = event.getItem();
    if (!MediaItemCodec.isHorn(item)) return;
    Player player = event.getPlayer();
    if (wouldUseClickedBlock(action, event.getClickedBlock(), player)) return;
    if (player.hasCooldown(Material.GOAT_HORN)) return;

    int cooldown = MediaFeature.get().getMediaConfig().getHornCooldownTicks();
    Bukkit.getScheduler().runTaskLater(
      MediaFeature.get().getJavaPlugin(),
      () -> player.setCooldown(Material.GOAT_HORN, cooldown),
      1L);

    MediaItemCodec.HornData horn = MediaItemCodec.readHorn(item);
    AudioEngine.getInstance().playHorn(player, horn.source(), horn.volume());
  }

  private static boolean wouldUseClickedBlock(Action action, Block clicked, Player player) {
    return action == Action.RIGHT_CLICK_BLOCK
      && clicked != null
      && clicked.getType().isInteractable()
      && !player.isSneaking();
  }
}
