package net.crabcraft.customdiscs.event;

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
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.audio.AudioEngine;
import net.crabcraft.customdiscs.util.HornUtil;

/**
 * Plays custom audio when a player blows a custom goat horn. The vanilla horn sound is silenced by
 * pointing the {@code minecraft:instrument} component at the silent {@code intentionally_empty} sound
 * in {@link net.crabcraft.customdiscs.util.HornFactory} (the blower's client prediction fires before
 * the server can cancel the event, so cancellation alone cannot suppress it). The horn stays a usable
 * instrument, so the vanilla blowing animation still plays for the blower and everyone watching — the
 * interact event is therefore left uncancelled and the vanilla use is allowed to proceed. The
 * configured cooldown is applied (a tick later, so it cannot suppress the toot fired this tick) and the
 * custom audio streams. Only the main hand is handled.
 */
public class HornHandler implements Listener {
  @EventHandler(priority = EventPriority.NORMAL)
  public void onBlowHorn(PlayerInteractEvent event) {
    // RIGHT_CLICK fires once per hand; only act on the main hand (create requires it there too).
    if (event.getHand() != EquipmentSlot.HAND) return;

    Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

    ItemStack item = event.getItem();
    if (!HornUtil.isCustomHorn(item)) return;

    Player player = event.getPlayer();

    // Vanilla item-use precedence: right-clicking an interactable block (chest, door, …) without
    // sneaking uses the block, not the horn. Don't blow in that case.
    if (action == Action.RIGHT_CLICK_BLOCK) {
      Block block = event.getClickedBlock();
      if (block != null && block.getType().isInteractable() && !player.isSneaking()) return;
    }

    // Don't cancel: the horn's instrument is silent (see HornFactory), so letting the vanilla use
    // proceed plays the blowing animation for the blower and everyone watching without any sound.
    // If on cooldown, vanilla won't toot either, so just stop here.
    if (player.hasCooldown(Material.GOAT_HORN)) return;

    // Apply the re-blow cooldown a tick later. Setting it now would run before the vanilla use is
    // processed this tick and could suppress the toot; the horn's use duration prevents an immediate
    // re-blow in the meantime.
    int cooldownTicks = CustomDiscs.getPlugin().getCDConfig().getHornCooldownTicks();
    CustomDiscs.getPlugin().getFoliaLib().getScheduler()
      .runAtEntityLater(player, task -> player.setCooldown(Material.GOAT_HORN, cooldownTicks), 1L);

    HornUtil.HornData horn = HornUtil.getHornData(item);
    CustomDiscs.debug("Custom horn blown by {}", player.getName());
    AudioEngine.getInstance().playHorn(player, horn.identifier(), horn.volume());
  }
}
