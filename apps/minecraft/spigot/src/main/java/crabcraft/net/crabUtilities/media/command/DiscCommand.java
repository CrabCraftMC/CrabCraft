package crabcraft.net.crabUtilities.media.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.dialog.CreateDiscDialog;
import crabcraft.net.crabUtilities.media.item.PlayableItemWriter;

import java.util.Collection;
import java.util.List;

/** {@code /disc} creates, edits, or clears playable music discs. */
@SuppressWarnings("UnstableApiUsage")
public final class DiscCommand implements BasicCommand {
  @Override
  public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
    if (!(source.getSender() instanceof Player player)) {
      MediaFeature.sendMessage(source.getSender(),
        MediaFeature.get().getMessages().prefixedComponent("error.command.cant-perform"));
      return;
    }
    String subcommand = args.length > 0 ? args[0].toLowerCase() : "create";
    switch (subcommand) {
      case "edit" -> CreateDiscDialog.openForEdit(player);
      case "clear" -> PlayableItemWriter.clearDisc(player);
      default -> CreateDiscDialog.open(player);
    }
  }

  @Override
  public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
    return args.length <= 1 ? List.of("create", "edit", "clear") : List.of();
  }

  @Override
  public @Nullable String permission() {
    return "crabutilities.media.create";
  }
}
