package crabcraft.net.crabUtilities.media.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.dialog.CreateDiscDialog;

import java.util.Collection;
import java.util.List;

/** {@code /disc} opens the custom music disc creation dialog. */
@SuppressWarnings("UnstableApiUsage")
public final class DiscCommand implements BasicCommand {
  @Override
  public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
    if (!(source.getSender() instanceof Player player)) {
      MediaFeature.sendMessage(source.getSender(),
        MediaFeature.get().getMessages().prefixedComponent("error.command.cant-perform"));
      return;
    }
    if (args.length > 0 && args[0].equalsIgnoreCase("edit")) {
      CreateDiscDialog.openForEdit(player);
    } else {
      CreateDiscDialog.open(player);
    }
  }

  @Override
  public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
    return args.length <= 1 ? List.of("create", "edit") : List.of();
  }

  @Override
  public @Nullable String permission() {
    return "crabutilities.media.create";
  }
}
