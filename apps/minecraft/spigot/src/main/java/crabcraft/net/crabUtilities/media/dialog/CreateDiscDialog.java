package crabcraft.net.crabUtilities.media.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.item.MediaItemCodec;
import crabcraft.net.crabUtilities.media.item.PlayableItemWriter;
import crabcraft.net.crabUtilities.media.language.MediaMessages;

import java.util.List;

/**
 * The {@code /disc create} and {@code /disc edit} dialogs: two text inputs (link + name) and a Create button
 * whose inline callback writes the disc on the player's region thread.
 */
@SuppressWarnings("UnstableApiUsage")
public final class CreateDiscDialog {
  private CreateDiscDialog() {}

  /** {@code /disc create}: opens the creation dialog for a held music disc. */
  public static void open(Player player) {
    MediaFeature plugin = MediaFeature.get();
    MediaMessages lang = plugin.getMessages();
    if (!player.hasPermission("crabutilities.media.create")) {
      MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"));
      return;
    }
    if (!MediaItemCodec.isMusicDiscHeld(player)) {
      MediaFeature.sendMessage(player, lang.prefixedComponent("command.create.messages.error.not-holding-disc"));
      return;
    }
    show(player, "command.create.dialog.title", "", "", 100, plugin.getMediaConfig().getDiscRangeDefault());
  }

  /** {@code /disc edit}: opens the dialog pre-filled with the held media disc's current properties. */
  public static void openForEdit(Player player) {
    MediaFeature plugin = MediaFeature.get();
    MediaMessages lang = plugin.getMessages();
    if (!player.hasPermission("crabutilities.media.create")) {
      MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"));
      return;
    }
    ItemStack held = player.getInventory().getItemInMainHand();
    if (!MediaItemCodec.isDisc(held)) {
      MediaFeature.sendMessage(player, lang.prefixedComponent("command.edit.messages.error.not-media-disc"));
      return;
    }
    MediaItemCodec.DiscData disc = MediaItemCodec.readDisc(held);
    int volPercent = Math.round(disc.volume() * 100);
    int distance = disc.range() > 0 ? disc.range() : plugin.getMediaConfig().getDiscRangeDefault();
    show(player, "command.edit.dialog.title", disc.source(), disc.storedName(), volPercent, distance);
  }

  @SuppressWarnings("UnstableApiUsage")
  private static void show(Player player, String titleKey, String initUrl, String initName,
                           int initVolumePercent, int initDistance) {
    MediaFeature plugin = MediaFeature.get();
    MediaMessages lang = plugin.getMessages();
    int rangeMin = plugin.getMediaConfig().getDiscRangeMin();
    int rangeMax = plugin.getMediaConfig().getDiscRangeMax();
    int clampedDistance = Math.max(rangeMin, Math.min(rangeMax, initDistance));

    Dialog dialog = Dialog.create(b -> b.empty()
      .base(DialogBase.builder(lang.component(titleKey))
        .body(List.of(DialogBody.plainMessage(lang.component("command.create.dialog.body"))))
        .inputs(List.of(
          DialogInput.text("url", lang.component("command.create.dialog.input.url"))
            .initial(initUrl).maxLength(512).width(300).build(),
          DialogInput.text("name", lang.component("command.create.dialog.input.name"))
            .initial(initName).maxLength(64).width(300).build(),
          DialogInput.numberRange("volume", lang.component("command.create.dialog.input.volume"), 0f, 200f)
            .step(5f).initial((float) initVolumePercent).labelFormat("%s: %s%%").width(300).build(),
          DialogInput.numberRange("distance", lang.component("command.create.dialog.input.distance"),
              (float) rangeMin, (float) rangeMax)
            .step(1f).initial((float) clampedDistance).labelFormat("%s: %s blocks").width(300).build()
        ))
        .canCloseWithEscape(true)
        .build())
      .type(DialogType.confirmation(
        ActionButton.create(
          lang.component("command.create.dialog.button.cancel"), null, 100, null),
        ActionButton.create(
          lang.component("command.create.dialog.button.create"), null, 100,
          DialogAction.customClick(CreateDiscDialog::onSubmit,
            ClickCallback.Options.builder().uses(1).build()))
      )));

    player.showDialog(dialog);
  }

  private static void onSubmit(DialogResponseView view, Audience audience) {
    if (!(audience instanceof Player player)) return;
    String url = view.getText("url");
    String name = view.getText("name");
    Float volPercent = view.getFloat("volume");
    float volume = volPercent != null ? volPercent / 100f : 1f;
    Float distF = view.getFloat("distance");
    int distance = distF != null ? Math.round(distF) : 0;
    Bukkit.getScheduler().runTask(
      MediaFeature.get().getJavaPlugin(),
      () -> PlayableItemWriter.writeDisc(player, url, name, volume, distance));
  }
}
