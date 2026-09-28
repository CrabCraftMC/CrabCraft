package crabcraft.net.crabUtilities.moderation;

import crabcraft.net.crabUtilities.CrabMessages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SmiteCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("crabutilities.smite")) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", CrabMessages.ERROR));
            return true;
        }

        if (args.length != 1) {
//            sender.sendMessage(Component.text("Usage: /smite <player>", CrabMessages.ERROR));
            return false;
        }

        String username = args[0];
        Bukkit.getOnlinePlayers().forEach(p -> {
           if (p.getName().equalsIgnoreCase(username) && p.isOnline() ) {
               p.getWorld().strikeLightningEffect(p.getLocation());
               p.setVelocity(new Vector(0, 0.5, 0));
           }
        });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream().map((Player::getName)).toList();
        }

        return List.of();
    }
}
