package crabcraft.net.crabUtilities.velocity;

import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Proxy command so the same state follows staff to every backend. */
public final class VanishCommand {

    private VanishCommand() {
    }

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("vanish")
                .requires(source -> source instanceof Player player && VanishManager.canVanish(player))
                .executes(context -> {
                    Player player = (Player) context.getSource();
                    return update(plugin, player, !plugin.getVanishManager().isVanished(player));
                })
                .then(BrigadierCommand.literalArgumentBuilder("on")
                        .executes(context -> update(plugin, (Player) context.getSource(), true)))
                .then(BrigadierCommand.literalArgumentBuilder("off")
                        .executes(context -> update(plugin, (Player) context.getSource(), false)))
                .build();
        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .aliases("v").plugin(plugin).build(), command);
    }

    private static int update(CrabUtilitiesVelocity plugin, Player player, boolean vanished) {
        if (!plugin.getVanishManager().setVanished(player, vanished)) {
            player.sendMessage(Component.text("Could not save your vanish state. Please check the proxy logs.",
                    NamedTextColor.RED));
            return 0;
        }
        player.sendMessage(Component.text(vanished
                        ? "Vanish enabled. Staff can still see you."
                        : "Vanish disabled.",
                vanished ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        return 1;
    }
}
