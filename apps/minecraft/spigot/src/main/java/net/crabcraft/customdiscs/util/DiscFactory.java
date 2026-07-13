package net.crabcraft.customdiscs.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.Keys;

/**
 * Creates remote custom discs by writing source metadata onto a held music disc.
 * Must be called on the player's region thread (inventory mutation).
 */
public final class DiscFactory {
  private DiscFactory() {}

  @SuppressWarnings("UnstableApiUsage")
  public static void applyRemote(Player player, String url, String name, float volume, int distance) {
    CustomDiscs plugin = CustomDiscs.getPlugin();

    if (url == null || url.isBlank()) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("error.command.url-empty"));
      return;
    }

    RemoteServices service = RemoteServices.fromUrl(url);
    if (service == null
        || !player.hasPermission("customdiscs.create.remote.%s".formatted(service.getId()))) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("error.command.no-permission"));
      return;
    }

    if (!LegacyUtil.isMusicDiscInHand(player)) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.create.messages.error.not-holding-disc"));
      return;
    }

    if (name == null || name.isBlank()) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("error.command.disc-name-empty"));
      return;
    }

    ItemStack disc = new ItemStack(player.getInventory().getItemInMainHand());
    ItemMeta meta = LegacyUtil.getItemMeta(disc);

    String trimmedName = name.length() > 32 ? name.substring(0, 32) + "..." : name;
    meta.displayName(Component.text(trimmedName)
      .color(NamedTextColor.WHITE)
      .decoration(TextDecoration.ITALIC, false));

    meta.addItemFlags(ItemFlag.values());

    // NOTE: this writes the model data onto the throwaway `disc` copy, not the held
    // item that receives `meta` below, so it is currently a no-op. Behaviour preserved
    // verbatim from the original RemoteCreateSubCommand (default custom-model is 0).
    int modelData = service.getCustomModelData();
    if (modelData != 0)
      disc.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(modelData).build());

    PersistentDataContainer data = meta.getPersistentDataContainer();
    data.remove(Keys.REMOTE_DISC.key());
    data.remove(Keys.DISC_VOLUME.key());
    data.remove(Keys.DISC_DISTANCE.key());
    data.remove(Keys.DISC_NAME.key());
    data.remove(Keys.LEGACY_REMOTE_DISC.key());
    data.remove(Keys.LEGACY_YOUTUBE_DISC.key());
    data.remove(Keys.LEGACY_SOUNDCLOUD_DISC.key());
    data.set(Keys.REMOTE_DISC.key(), Keys.REMOTE_DISC.dataType(), url);
    data.set(Keys.DISC_VOLUME.key(), Keys.DISC_VOLUME.dataType(), volume);
    data.set(Keys.DISC_DISTANCE.key(), Keys.DISC_DISTANCE.dataType(), distance);
    data.set(Keys.DISC_NAME.key(), Keys.DISC_NAME.dataType(), name);

    player.getInventory().getItemInMainHand().setItemMeta(meta);

    var lang = plugin.getLanguage();
    CustomDiscs.sendMessage(player, lang.component("command.create.messages.created"));
    CustomDiscs.sendMessage(player, lang.component("command.create.messages.name", name));
    CustomDiscs.sendMessage(player, lang.component("command.create.messages.source", url));
    int volumePercent = Math.round(volume * 100);
    if (volumePercent != 100) {
      CustomDiscs.sendMessage(player, lang.component("command.create.messages.volume", String.valueOf(volumePercent)));
    }
    if (distance > 0) {
      CustomDiscs.sendMessage(player, lang.component("command.create.messages.distance", String.valueOf(distance)));
    }
  }
}
