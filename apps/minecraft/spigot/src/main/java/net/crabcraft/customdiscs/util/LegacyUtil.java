package net.crabcraft.customdiscs.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.jetbrains.annotations.NotNull;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.Keys;
import net.crabcraft.customdiscs.api.DiscEntry;

import java.util.List;
import java.util.UUID;

public class LegacyUtil {
  public static boolean isJukeboxContainsDisc(@NotNull Block block) {
    Jukebox jukebox = (Jukebox) block.getLocation().getBlock().getState();
    return jukebox.getRecord().getType() != Material.AIR;
  }

  private static boolean isRemoteDisc(@NotNull ItemStack item) {
    return getItemMeta(item).getPersistentDataContainer()
      .has(Keys.REMOTE_DISC.key(), Keys.REMOTE_DISC.dataType());
  }

  public static boolean isCustomDisc(@NotNull ItemStack item) {
    {
      ItemMeta meta = getItemMeta(item);
      if (migratePDC(meta.getPersistentDataContainer()))
        item.setItemMeta(meta);
    }
    return isRemoteDisc(item);
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public static boolean isMusicDiscInHand(Player player) {
    return player.getInventory().getItemInMainHand().getType().toString().contains("MUSIC_DISC");
  }

  public static ItemMeta getItemMeta(ItemStack itemStack) {
    ItemMeta meta;

    if ((meta = itemStack.getItemMeta()) == null)
      throw new IllegalStateException("Why item meta is null!?");

    return meta;
  }

  public static UUID getBlockUUID(Block block) {
    long msb = block.getWorld().getUID().getLeastSignificantBits() ^ block.getY();
    long lsb = ((long) block.getX() << 32) | (block.getZ() & 0xFFFFFFFFL);
    return new UUID(msb, lsb);
  }

  @SuppressWarnings("unchecked")
  private static boolean migratePDC(PersistentDataContainer data) {
    Keys.Key<String>[] legacyRemoteKeys = new Keys.Key[]{
      Keys.LEGACY_REMOTE_DISC,
      Keys.LEGACY_YOUTUBE_DISC,
      Keys.LEGACY_SOUNDCLOUD_DISC
    };

    for (Keys.Key<String> key : legacyRemoteKeys) {
      String legacyRemoteValue = data.get(key.key(), key.dataType());
      if (legacyRemoteValue != null) {
        data.remove(key.key());
        data.set(Keys.REMOTE_DISC.key(), Keys.REMOTE_DISC.dataType(), legacyRemoteValue);
        return true;
      }
    }

    return false;
  }

  public static DiscEntry getDiscEntry(ItemStack disc) {
    ItemMeta meta = getItemMeta(disc);
    PersistentDataContainer data = meta.getPersistentDataContainer();

    String remote = data.get(Keys.REMOTE_DISC.key(), Keys.REMOTE_DISC.dataType());
    if (remote != null) {
      Float vol = data.get(Keys.DISC_VOLUME.key(), Keys.DISC_VOLUME.dataType());
      Integer dist = data.get(Keys.DISC_DISTANCE.key(), Keys.DISC_DISTANCE.dataType());
      return new DiscEntry(disc, getSongName(meta), remote, false,
        vol != null ? vol : 1.0f, dist != null ? dist : 0);
    }

    throw new IllegalArgumentException();
  }

  private static Component getSongName(ItemMeta meta) {
    List<Component> lore = meta.lore();
    if (lore == null || lore.isEmpty())
      return Component.text("Unknown").color(NamedTextColor.GRAY);

    return lore.getFirst();
  }
}
