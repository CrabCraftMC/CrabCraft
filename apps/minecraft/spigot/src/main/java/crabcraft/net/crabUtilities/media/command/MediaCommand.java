package crabcraft.net.crabUtilities.media.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import crabcraft.net.crabUtilities.CrabMessages;
import crabcraft.net.crabUtilities.media.MediaFeature;
import crabcraft.net.crabUtilities.media.dialog.CreateDiscDialog;
import crabcraft.net.crabUtilities.media.dialog.CreateHornDialog;
import crabcraft.net.crabUtilities.media.item.PlayableItemWriter;
import crabcraft.net.crabUtilities.media.language.MediaMessages;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** {@code /cd}: help and disc/horn management shortcuts. */
@SuppressWarnings("UnstableApiUsage")
public final class MediaCommand implements BasicCommand {
  @Override
  public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
    CommandSender sender = source.getSender();
    if (args.length == 0) {
      sendUsage(sender);
      return;
    }

    MediaFeature plugin = MediaFeature.get();
    String sub = args[0].toLowerCase();

    switch (sub) {
      case "create" -> {
        if (sender instanceof Player player) {
          CreateDiscDialog.open(player);
        } else {
          MediaFeature.sendMessage(sender, plugin.getMessages().prefixedComponent("error.command.cant-perform"));
        }
      }
      case "edit" -> {
        if (sender instanceof Player player) {
          CreateDiscDialog.openForEdit(player);
        } else {
          MediaFeature.sendMessage(sender, plugin.getMessages().prefixedComponent("error.command.cant-perform"));
        }
      }
      case "clear" -> {
        if (sender instanceof Player player) {
          PlayableItemWriter.clearDisc(player);
        } else {
          MediaFeature.sendMessage(sender, plugin.getMessages().prefixedComponent("error.command.cant-perform"));
        }
      }
      case "horn" -> {
        if (sender instanceof Player player) {
          String hornSub = args.length > 1 ? args[1].toLowerCase() : "create";
          switch (hornSub) {
            case "edit" -> CreateHornDialog.openForEdit(player);
            case "clear" -> PlayableItemWriter.clearHorn(player);
            default -> CreateHornDialog.open(player);
          }
        } else {
          MediaFeature.sendMessage(sender, plugin.getMessages().prefixedComponent("error.command.cant-perform"));
        }
      }
      case "help" -> sendHelp(sender);
      default -> sendUsage(sender);
    }
  }

  private static void sendUsage(CommandSender sender) {
    MediaFeature.sendMessage(sender, CrabMessages.error("Usage: /cd <create|edit|clear|horn>"));
  }

  private void sendHelp(CommandSender sender) {
    MediaMessages lang = MediaFeature.get().getMessages();
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.header"));
    if (!sender.hasPermission("crabutilities.media.create")) return;

    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.create.syntax"), lang.string("command.create.description")));
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.edit.syntax"), lang.string("command.edit.description")));
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.clear.syntax"), lang.string("command.clear.description")));
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.horn.create.syntax"), lang.string("command.horn.create.description")));
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.horn.edit.syntax"), lang.string("command.horn.edit.description")));
    MediaFeature.sendMessage(sender, lang.component("command.help.messages.format",
      lang.string("command.horn.clear.syntax"), lang.string("command.horn.clear.description")));
  }

  @Override
  public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
    CommandSender sender = source.getSender();
    // Second argument of "/cd horn <create|edit|clear>".
    if (args.length == 2 && args[0].equalsIgnoreCase("horn")) {
      if (!sender.hasPermission("crabutilities.media.create")) return List.of();
      return List.of("create", "edit", "clear").stream()
        .filter(s -> s.startsWith(args[1].toLowerCase())).toList();
    }
    if (args.length > 1) return List.of();
    List<String> out = new ArrayList<>();
    out.add("help");
    if (sender.hasPermission("crabutilities.media.create")) out.add("create");
    if (sender.hasPermission("crabutilities.media.create")) out.add("edit");
    if (sender.hasPermission("crabutilities.media.create")) out.add("clear");
    if (sender.hasPermission("crabutilities.media.create")) out.add("horn");
    String prefix = args.length == 1 ? args[0].toLowerCase() : "";
    return out.stream().filter(s -> s.startsWith(prefix)).toList();
  }
}
