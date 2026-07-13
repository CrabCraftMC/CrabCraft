package net.crabcraft.customdiscs.dialog;

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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.Keys;
import net.crabcraft.customdiscs.language.YamlLanguage;
import net.crabcraft.customdiscs.util.DiscFactory;
import net.crabcraft.customdiscs.util.LegacyUtil;

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
    CustomDiscs plugin = CustomDiscs.getPlugin();
    YamlLanguage lang = plugin.getLanguage();
    if (!player.hasPermission("customdiscs.create")) {
      CustomDiscs.sendMessage(player, lang.PComponent("error.command.no-permission"));
      return;
    }
    if (!LegacyUtil.isMusicDiscInHand(player)) {
      CustomDiscs.sendMessage(player, lang.PComponent("command.create.messages.error.not-holding-disc"));
      return;
    }
    show(player, "command.create.dialog.title", "", "", 100, plugin.getCDConfig().getDiscRangeDefault());
  }

  /** {@code /disc edit}: opens the dialog pre-filled with the held custom disc's current properties. */
  public static void openForEdit(Player player) {
    CustomDiscs plugin = CustomDiscs.getPlugin();
    YamlLanguage lang = plugin.getLanguage();
    if (!player.hasPermission("customdiscs.create")) {
      CustomDiscs.sendMessage(player, lang.PComponent("error.command.no-permission"));
      return;
    }
    ItemStack held = player.getInventory().getItemInMainHand();
    if (!LegacyUtil.isCustomDisc(held)) {
      CustomDiscs.sendMessage(player, lang.PComponent("command.edit.messages.error.not-custom-disc"));
      return;
    }
    PersistentDataContainer data = LegacyUtil.getItemMeta(held).getPersistentDataContainer();
    String url = data.get(Keys.REMOTE_DISC.key(), Keys.REMOTE_DISC.dataType());
    String name = data.get(Keys.DISC_NAME.key(), Keys.DISC_NAME.dataType());
    Float vol = data.get(Keys.DISC_VOLUME.key(), Keys.DISC_VOLUME.dataType());
    Integer dist = data.get(Keys.DISC_DISTANCE.key(), Keys.DISC_DISTANCE.dataType());
    int volPercent = vol != null ? Math.round(vol * 100) : 100;
    int distance = (dist != null && dist > 0) ? dist : plugin.getCDConfig().getDiscRangeDefault();
    show(player, "command.edit.dialog.title", url != null ? url : "", name != null ? name : "", volPercent, distance);
  }

  @SuppressWarnings("UnstableApiUsage")
  private static void show(Player player, String titleKey, String initUrl, String initName,
                           int initVolumePercent, int initDistance) {
    CustomDiscs plugin = CustomDiscs.getPlugin();
    YamlLanguage lang = plugin.getLanguage();
    int rangeMin = plugin.getCDConfig().getDiscRangeMin();
    int rangeMax = plugin.getCDConfig().getDiscRangeMax();
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
    CustomDiscs.getPlugin().getFoliaLib().getScheduler()
      .runAtEntity(player, task -> DiscFactory.applyRemote(player, url, name, volume, distance));
  }
}
