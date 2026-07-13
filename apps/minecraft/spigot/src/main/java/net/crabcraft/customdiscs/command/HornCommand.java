package net.crabcraft.customdiscs.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.dialog.CreateHornDialog;

import java.util.Collection;
import java.util.List;

/** {@code /horn} opens the custom goat horn creation dialog. */
@SuppressWarnings("UnstableApiUsage")
public final class HornCommand implements BasicCommand {
  @Override
  public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
    if (!(source.getSender() instanceof Player player)) {
      CustomDiscs.sendMessage(source.getSender(),
        CustomDiscs.getPlugin().getLanguage().PComponent("error.command.cant-perform"));
      return;
    }
    if (args.length > 0 && args[0].equalsIgnoreCase("edit")) {
      CreateHornDialog.openForEdit(player);
    } else {
      CreateHornDialog.open(player);
    }
  }

  @Override
  public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
    return args.length <= 1 ? List.of("create", "edit") : List.of();
  }

  @Override
  public @Nullable String permission() {
    return "customdiscs.create";
  }
}
