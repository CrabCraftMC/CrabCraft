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
 * The {@code /horn create} and {@code /horn edit} dialogs: mirrors {@link CreateDiscDialog} but for
 * a held goat horn, reading/writing the {@code HORN_*} keys.
 */
@SuppressWarnings("UnstableApiUsage")
public final class CreateHornDialog {
  private CreateHornDialog() {}

  /** {@code /horn create}: opens the creation dialog for a held goat horn. */
  public static void open(Player player) {
    MediaFeature plugin = MediaFeature.get();
    MediaMessages lang = plugin.getMessages();
    if (!player.hasPermission("crabutilities.media.create")) {
      MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"));
      return;
    }
    if (!MediaItemCodec.isGoatHornHeld(player)) {
      MediaFeature.sendMessage(player, lang.prefixedComponent("command.horn.create.messages.error.not-holding-horn"));
      return;
    }
    show(player, "command.horn.create.dialog.title", "", "", 100);
  }

  /** {@code /horn edit}: opens the dialog pre-filled with the held media horn's current properties. */
  public static void openForEdit(Player player) {
    MediaFeature plugin = MediaFeature.get();
    MediaMessages lang = plugin.getMessages();
    if (!player.hasPermission("crabutilities.media.create")) {
      MediaFeature.sendMessage(player, lang.prefixedComponent("error.command.no-permission"));
      return;
    }
    ItemStack held = player.getInventory().getItemInMainHand();
    if (!MediaItemCodec.isHorn(held)) {
      MediaFeature.sendMessage(player, lang.prefixedComponent("command.horn.edit.messages.error.not-media-horn"));
      return;
    }
    MediaItemCodec.HornData horn = MediaItemCodec.readHorn(held);
    int volPercent = Math.round(horn.volume() * 100);
    show(player, "command.horn.edit.dialog.title", horn.source(), horn.storedName(), volPercent);
  }

  private static void show(Player player, String titleKey, String initUrl, String initName,
                           int initVolumePercent) {
    MediaFeature plugin = MediaFeature.get();
    MediaMessages lang = plugin.getMessages();

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
    Bukkit.getScheduler().runTask(
      MediaFeature.get().getJavaPlugin(),
      () -> PlayableItemWriter.writeHorn(player, url, name, volume));
  }
}
