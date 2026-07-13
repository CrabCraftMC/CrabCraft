package net.crabcraft.customdiscs.util;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.registry.keys.SoundEventKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.MusicInstrument;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.Keys;
import net.crabcraft.customdiscs.audio.AudioEngine;

/**
 * Creates custom goat horns by writing source metadata onto a held goat horn. Mirrors
 * {@link DiscFactory}. Must be called on the player's region thread (inventory mutation).
 */
public final class HornFactory {
  private HornFactory() {}

  @SuppressWarnings("UnstableApiUsage")
  public static void applyRemote(Player player, String url, String name, float volume) {
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

    if (!HornUtil.isGoatHornInHand(player)) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("command.horn.create.messages.error.not-holding-horn"));
      return;
    }

    if (name == null || name.isBlank()) {
      CustomDiscs.sendMessage(player, plugin.getLanguage().PComponent("error.command.horn-name-empty"));
      return;
    }

    ItemStack horn = new ItemStack(player.getInventory().getItemInMainHand());
    ItemMeta meta = LegacyUtil.getItemMeta(horn);

    String trimmedName = name.length() > 32 ? name.substring(0, 32) + "..." : name;
    meta.displayName(Component.text(trimmedName)
      .color(NamedTextColor.WHITE)
      .decoration(TextDecoration.ITALIC, false));

    meta.addItemFlags(ItemFlag.values());

    // Replace only CustomDiscs metadata; keys owned by other plugins must survive editing.
    PersistentDataContainer data = meta.getPersistentDataContainer();
    data.remove(Keys.HORN_REMOTE.key());
    data.remove(Keys.HORN_VOLUME.key());
    data.remove(Keys.HORN_NAME.key());
    data.set(Keys.HORN_REMOTE.key(), Keys.HORN_REMOTE.dataType(), url);
    data.set(Keys.HORN_VOLUME.key(), Keys.HORN_VOLUME.dataType(), volume);
    data.set(Keys.HORN_NAME.key(), Keys.HORN_NAME.dataType(), name);

    ItemStack inHand = player.getInventory().getItemInMainHand();
    inHand.setItemMeta(meta);
    // Replace the goat horn's instrument with a silent one instead of stripping it. The horn's own
    // client predicts and plays the vanilla sound locally (the server broadcast excludes the blower),
    // so cancelling the interact event can't mute it for them — but removing the instrument entirely
    // also kills the TOOT_HORN use animation, since a horn with no instrument has a use duration of 0
    // and its use() does nothing. Pointing the instrument at the registered `intentionally_empty`
    // sound keeps the horn fully usable — so the toot animation plays for the blower and everyone
    // watching — while producing no sound at all (and no client "unknown soundEvent" warning). The
    // use duration drives how long the animation lasts, so we tie it to the audio length cap.
    float tootDuration = Math.max(1, plugin.getCDConfig().getHornMaxLengthSeconds());
    inHand.setData(DataComponentTypes.INSTRUMENT, MusicInstrument.create(factory -> factory.empty()
      .soundEvent(SoundEventKeys.INTENTIONALLY_EMPTY)
      .duration(tootDuration)
      .range(16f)
      .description(Component.text(trimmedName))));

    var lang = plugin.getLanguage();
    CustomDiscs.sendMessage(player, lang.component("command.horn.create.messages.created"));
    CustomDiscs.sendMessage(player, lang.component("command.horn.create.messages.name", name));
    CustomDiscs.sendMessage(player, lang.component("command.horn.create.messages.source", url));
    int volumePercent = Math.round(volume * 100);
    if (volumePercent != 100) {
      CustomDiscs.sendMessage(player, lang.component("command.horn.create.messages.volume", String.valueOf(volumePercent)));
    }

    // Best-effort, off-thread: decode the first `cap` seconds into the on-disk cache so the first
    // blow plays instantly, and warn if the source is longer than the playback cap.
    int cap = plugin.getCDConfig().getHornMaxLengthSeconds();
    float effectiveVolume = plugin.getCDConfig().getHornVolume() * volume;
    AudioEngine.getInstance().prewarmHornAsync(url, effectiveVolume, track -> {
      if (track != null && track.durationSeconds() != null && track.durationSeconds() > cap) {
        CustomDiscs.sendMessage(player,
          lang.component("command.horn.create.messages.length-warning", String.valueOf(cap)));
      }
    });
  }
}
