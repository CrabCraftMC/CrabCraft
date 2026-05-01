package crabcraft.net.crabUtilities.velocity.messaging;

import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class SocialSpyCommand {

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("socialspy")
                .requires(source -> source instanceof Player
                        && source.hasPermission(MessageManager.SOCIALSPY_PERMISSION))
                .executes(ctx -> {
                    Player player = (Player) ctx.getSource();
                    boolean nowEnabled = plugin.getMessageManager().toggleSpy(player.getUniqueId());

                    if (nowEnabled) {
                        player.sendMessage(Component.text(
                                "Social spy enabled.", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text(
                                "Social spy disabled.", NamedTextColor.RED));
                    }
                    return 1;
                })
                .build();

        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .aliases("sspy")
                        .plugin(plugin)
                        .build(),
                command
        );
    }
}
