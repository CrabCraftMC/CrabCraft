package net.crabcraft.customdiscs.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import net.crabcraft.customdiscs.CustomDiscs;
import net.crabcraft.customdiscs.dialog.CreateDiscDialog;
import net.crabcraft.customdiscs.dialog.CreateHornDialog;
import net.crabcraft.customdiscs.language.YamlLanguage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** {@code /customdiscs} (alias {@code /cd}): reload, help, and the create alias. */
@SuppressWarnings("UnstableApiUsage")
public final class CustomDiscsCommand implements BasicCommand {
  @Override
  public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
    CommandSender sender = source.getSender();
    CustomDiscs plugin = CustomDiscs.getPlugin();
    String sub = args.length > 0 ? args[0].toLowerCase() : "help";

    switch (sub) {
      case "reload" -> {
        if (!sender.hasPermission("customdiscs.reload")) {
          CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("error.command.no-permission"));
          return;
        }
        plugin.getCDConfig().load();
        plugin.getLanguage().load();
        CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("command.reload.messages.successfully"));
      }
      case "create" -> {
        if (sender instanceof Player player) {
          CreateDiscDialog.open(player);
        } else {
          CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("error.command.cant-perform"));
        }
      }
      case "edit" -> {
        if (sender instanceof Player player) {
          CreateDiscDialog.openForEdit(player);
        } else {
          CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("error.command.cant-perform"));
        }
      }
      case "horn" -> {
        if (sender instanceof Player player) {
          String hornSub = args.length > 1 ? args[1].toLowerCase() : "create";
          if (hornSub.equals("edit")) {
            CreateHornDialog.openForEdit(player);
          } else {
            CreateHornDialog.open(player);
          }
        } else {
          CustomDiscs.sendMessage(sender, plugin.getLanguage().PComponent("error.command.cant-perform"));
        }
      }
      default -> sendHelp(sender);
    }
  }

  private void sendHelp(CommandSender sender) {
    YamlLanguage lang = CustomDiscs.getPlugin().getLanguage();
    CustomDiscs.sendMessage(sender, lang.component("command.help.messages.header"));
    if (sender.hasPermission("customdiscs.create")) {
      CustomDiscs.sendMessage(sender, lang.component("command.help.messages.format",
        lang.string("command.create.syntax"), lang.string("command.create.description")));
    }
    if (sender.hasPermission("customdiscs.create")) {
      CustomDiscs.sendMessage(sender, lang.component("command.help.messages.format",
        lang.string("command.edit.syntax"), lang.string("command.edit.description")));
    }
    if (sender.hasPermission("customdiscs.create")) {
      CustomDiscs.sendMessage(sender, lang.component("command.help.messages.format",
        lang.string("command.horn.create.syntax"), lang.string("command.horn.create.description")));
    }
    if (sender.hasPermission("customdiscs.reload")) {
      CustomDiscs.sendMessage(sender, lang.component("command.help.messages.format",
        lang.string("command.reload.syntax"), lang.string("command.reload.description")));
    }
    CustomDiscs.sendMessage(sender, lang.component("command.help.messages.footer"));
  }

  @Override
  public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
    CommandSender sender = source.getSender();
    // Second argument of "/cd horn <create|edit>".
    if (args.length == 2 && args[0].equalsIgnoreCase("horn")) {
      if (!sender.hasPermission("customdiscs.create")) return List.of();
      return List.of("create", "edit").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
    }
    if (args.length > 1) return List.of();
    List<String> out = new ArrayList<>();
    out.add("help");
    if (sender.hasPermission("customdiscs.create")) out.add("create");
    if (sender.hasPermission("customdiscs.create")) out.add("edit");
    if (sender.hasPermission("customdiscs.create")) out.add("horn");
    if (sender.hasPermission("customdiscs.reload")) out.add("reload");
    String prefix = args.length == 1 ? args[0].toLowerCase() : "";
    return out.stream().filter(s -> s.startsWith(prefix)).toList();
  }
}
