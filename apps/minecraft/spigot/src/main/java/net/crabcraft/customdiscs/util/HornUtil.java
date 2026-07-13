package net.crabcraft.customdiscs.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.jetbrains.annotations.NotNull;
import net.crabcraft.customdiscs.Keys;

/**
 * Detection + metadata extraction for custom goat horns, mirroring {@link LegacyUtil} for discs.
 * A custom horn is a {@link Material#GOAT_HORN} carrying the {@code HORN_REMOTE} key.
 */
public final class HornUtil {
  private HornUtil() {}

  /** The source URL and volume multiplier stored on a custom horn. */
  public record HornData(String identifier, float volume) {}

  public static boolean isGoatHornInHand(@NotNull Player player) {
    return player.getInventory().getItemInMainHand().getType() == Material.GOAT_HORN;
  }

  public static boolean isCustomHorn(ItemStack item) {
    if (item == null || item.getType() != Material.GOAT_HORN || !item.hasItemMeta()) return false;
    return LegacyUtil.getItemMeta(item).getPersistentDataContainer()
      .has(Keys.HORN_REMOTE.key(), Keys.HORN_REMOTE.dataType());
  }

  /** Reads the horn's stored source/volume. Call only when {@link #isCustomHorn} is true. */
  public static HornData getHornData(@NotNull ItemStack item) {
    PersistentDataContainer data = LegacyUtil.getItemMeta(item).getPersistentDataContainer();
    String identifier = data.get(Keys.HORN_REMOTE.key(), Keys.HORN_REMOTE.dataType());
    if (identifier == null) throw new IllegalArgumentException("not a custom horn");
    Float vol = data.get(Keys.HORN_VOLUME.key(), Keys.HORN_VOLUME.dataType());
    return new HornData(identifier, vol != null ? vol : 1.0f);
  }
}
