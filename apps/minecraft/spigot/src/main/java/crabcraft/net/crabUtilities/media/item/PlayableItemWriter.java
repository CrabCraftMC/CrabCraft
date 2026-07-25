package crabcraft.net.crabUtilities.media.item;

import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.audio.AudioEngine;
import crabcraft.net.crabUtilities.media.source.MediaSourceKind;
import crabcraft.net.crabUtilities.media.util.RemoteMediaSecurity;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.registry.keys.SoundEventKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.MusicInstrument;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Applies validated playback metadata to the item in a player's main hand. */
public final class PlayableItemWriter {
  private PlayableItemWriter() {}

  @SuppressWarnings("UnstableApiUsage")
  public static void writeDisc(
    Player player,
    String source,
    String name,
    float volume,
    int range
  ) {
    MediaFeature feature = MediaFeature.get();
    MediaSourceKind sourceKind = authorisedSource(player, source);
    if (sourceKind == null) return;
    if (!RemoteMediaSecurity.isValidDiscSettings(
      volume,
      range,
      feature.getMediaConfig().getDiscRangeMin(),
      feature.getMediaConfig().getDiscRangeMax()
    )) {
      reject(player, "error.command.invalid-settings");
      return;
    }
    if (!MediaItemCodec.isMusicDiscHeld(player)) {
      reject(player, "command.create.messages.error.not-holding-disc");
      return;
    }
    if (!hasName(player, name, "error.command.disc-name-empty")) return;

    ItemStack held = player.getInventory().getItemInMainHand();
    ItemMeta meta = MediaItemCodec.requireMeta(held);
    decorate(meta, name);
    MediaItemCodec.writeDisc(meta, source, name, volume, range);
    held.setItemMeta(meta);

    int model = sourceKind.itemModel(feature.getMediaConfig());
    if (model != 0) {
      held.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
        CustomModelData.customModelData().addFloat(model).build());
    }
    describeDisc(player, name, source, volume, range);
  }

  @SuppressWarnings("UnstableApiUsage")
  public static void writeHorn(Player player, String source, String name, float volume) {
    MediaFeature feature = MediaFeature.get();
    if (authorisedSource(player, source) == null) return;
    if (!RemoteMediaSecurity.isValidVolume(volume)) {
      reject(player, "error.command.invalid-settings");
      return;
    }
    if (!MediaItemCodec.isGoatHornHeld(player)) {
      reject(player, "command.horn.create.messages.error.not-holding-horn");
      return;
    }
    if (!hasName(player, name, "error.command.horn-name-empty")) return;

    ItemStack held = player.getInventory().getItemInMainHand();
    ItemMeta meta = MediaItemCodec.requireMeta(held);
    decorate(meta, name);
    MediaItemCodec.writeHorn(meta, source, name, volume);
    held.setItemMeta(meta);

    String title = shortened(name);
    float duration = Math.max(1, feature.getMediaConfig().getHornMaxLengthSeconds());
    held.setData(DataComponentTypes.INSTRUMENT, MusicInstrument.create(factory -> factory.empty()
      .soundEvent(SoundEventKeys.INTENTIONALLY_EMPTY)
      .duration(duration)
      .range(16f)
      .description(Component.text(title))));

    describeHorn(player, name, source, volume);
    prewarmHorn(player, source, volume);
  }

  private static MediaSourceKind authorisedSource(Player player, String source) {
    if (source == null || source.isBlank()) {
      reject(player, "error.command.url-empty");
      return null;
    }
    MediaFeature feature = MediaFeature.get();
    MediaSourceKind kind = MediaSourceKind.classify(source, feature.getMediaConfig());
    if (!RemoteMediaSecurity.canCreate(player::hasPermission, kind)) {
      reject(player, "error.command.no-permission");
      return null;
    }
    return kind;
  }

  private static boolean hasName(Player player, String name, String errorKey) {
    if (name != null && !name.isBlank()) return true;
    reject(player, errorKey);
    return false;
  }

  private static void decorate(ItemMeta meta, String name) {
    meta.displayName(Component.text(shortened(name), NamedTextColor.WHITE)
      .decoration(TextDecoration.ITALIC, false));
    meta.addItemFlags(ItemFlag.values());
  }

  private static String shortened(String name) {
    return name.length() <= 32 ? name : name.substring(0, 32) + "...";
  }

  private static void describeDisc(
    Player player,
    String name,
    String source,
    float volume,
    int range
  ) {
    var messages = MediaFeature.get().getMessages();
    player.sendMessage(messages.component("command.create.messages.created"));
    player.sendMessage(messages.component("command.create.messages.name", name));
    player.sendMessage(messages.component("command.create.messages.source", source));
    int percentage = Math.round(volume * 100);
    if (percentage != 100) {
      player.sendMessage(messages.component("command.create.messages.volume", percentage));
    }
    if (range > 0) {
      player.sendMessage(messages.component("command.create.messages.distance", range));
    }
  }

  private static void describeHorn(Player player, String name, String source, float volume) {
    var messages = MediaFeature.get().getMessages();
    player.sendMessage(messages.component("command.horn.create.messages.created"));
    player.sendMessage(messages.component("command.horn.create.messages.name", name));
    player.sendMessage(messages.component("command.horn.create.messages.source", source));
    int percentage = Math.round(volume * 100);
    if (percentage != 100) {
      player.sendMessage(messages.component("command.horn.create.messages.volume", percentage));
    }
  }

  private static void prewarmHorn(Player player, String source, float itemVolume) {
    MediaFeature feature = MediaFeature.get();
    int limit = feature.getMediaConfig().getHornMaxLengthSeconds();
    float effectiveVolume = feature.getMediaConfig().getHornVolume() * itemVolume;
    AudioEngine.getInstance().prewarmHornAsync(source, effectiveVolume, track -> {
      if (track != null && track.durationSeconds() != null && track.durationSeconds() > limit) {
        player.sendMessage(feature.getMessages()
          .component("command.horn.create.messages.length-warning", limit));
      }
    });
  }

  private static void reject(Player player, String messageKey) {
    player.sendMessage(MediaFeature.get().getMessages().prefixedComponent(messageKey));
  }
}
