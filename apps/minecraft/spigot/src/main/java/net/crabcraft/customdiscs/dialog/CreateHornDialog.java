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
import net.crabcraft.customdiscs.util.HornFactory;
import net.crabcraft.customdiscs.util.HornUtil;
import net.crabcraft.customdiscs.util.LegacyUtil;

import java.util.List;

/**
 * The {@code /horn create} and {@code /horn edit} dialogs: mirrors {@link CreateDiscDialog} but for
 * a held goat horn, reading/writing the {@code HORN_*} keys.
 */
@SuppressWarnings("UnstableApiUsage")
public final class CreateHornDialog {
  private CreateHornDialog() {}

  /** {@code /horn create}: opens the creation dialog for a held goat horn. */
  public static void open(Player player) {
    CustomDiscs plugin = CustomDiscs.getPlugin();
    YamlLanguage lang = plugin.getLanguage();
    if (!player.hasPermission("customdiscs.create")) {
      CustomDiscs.sendMessage(player, lang.PComponent("error.command.no-permission"));
      return;
    }
    if (!HornUtil.isGoatHornInHand(player)) {
      CustomDiscs.sendMessage(player, lang.PComponent("command.horn.create.messages.error.not-holding-horn"));
      return;
    }
    show(player, "command.horn.create.dialog.title", "", "", 100);
  }

  /** {@code /horn edit}: opens the dialog pre-filled with the held custom horn's current properties. */
  public static void openForEdit(Player player) {
    CustomDiscs plugin = CustomDiscs.getPlugin();
    YamlLanguage lang = plugin.getLanguage();
    if (!player.hasPermission("customdiscs.create")) {
      CustomDiscs.sendMessage(player, lang.PComponent("error.command.no-permission"));
      return;
    }
    ItemStack held = player.getInventory().getItemInMainHand();
    if (!HornUtil.isCustomHorn(held)) {
      CustomDiscs.sendMessage(player, lang.PComponent("command.horn.edit.messages.error.not-custom-horn"));
      return;
    }
    PersistentDataContainer data = LegacyUtil.getItemMeta(held).getPersistentDataContainer();
    String url = data.get(Keys.HORN_REMOTE.key(), Keys.HORN_REMOTE.dataType());
    String name = data.get(Keys.HORN_NAME.key(), Keys.HORN_NAME.dataType());
    Float vol = data.get(Keys.HORN_VOLUME.key(), Keys.HORN_VOLUME.dataType());
    int volPercent = vol != null ? Math.round(vol * 100) : 100;
    show(player, "command.horn.edit.dialog.title", url != null ? url : "", name != null ? name : "", volPercent);
  }

  private static void show(Player player, String titleKey, String initUrl, String initName,
                           int initVolumePercent) {
    CustomDiscs plugin = CustomDiscs.getPlugin();
    YamlLanguage lang = plugin.getLanguage();

    Dialog dialog = Dialog.create(b -> b.empty()
      .base(DialogBase.builder(lang.component(titleKey))
        .body(List.of(DialogBody.plainMessage(lang.component("command.horn.create.dialog.body"))))
        .inputs(List.of(
          DialogInput.text("url", lang.component("command.horn.create.dialog.input.url"))
            .initial(initUrl).maxLength(512).width(300).build(),
          DialogInput.text("name", lang.component("command.horn.create.dialog.input.name"))
            .initial(initName).maxLength(64).width(300).build(),
          DialogInput.numberRange("volume", lang.component("command.horn.create.dialog.input.volume"), 0f, 200f)
            .step(5f).initial((float) initVolumePercent).labelFormat("%s: %s%%").width(300).build()
        ))
        .canCloseWithEscape(true)
        .build())
      .type(DialogType.confirmation(
        ActionButton.create(
          lang.component("command.horn.create.dialog.button.cancel"), null, 100, null),
        ActionButton.create(
          lang.component("command.horn.create.dialog.button.create"), null, 100,
          DialogAction.customClick(CreateHornDialog::onSubmit,
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
    CustomDiscs.getPlugin().getFoliaLib().getScheduler()
      .runAtEntity(player, task -> HornFactory.applyRemote(player, url, name, volume));
  }
}
