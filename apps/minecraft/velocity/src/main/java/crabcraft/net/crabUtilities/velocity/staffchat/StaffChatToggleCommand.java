package crabcraft.net.crabUtilities.velocity.staffchat;

import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class StaffChatToggleCommand {

    public static void register(CrabUtilitiesVelocity plugin) {
        LiteralCommandNode<CommandSource> node = BrigadierCommand.literalArgumentBuilder("sctoggle")
                .requires(source -> source instanceof Player
                        && source.hasPermission(StaffChatManager.PERMISSION))
                .executes(ctx -> {
                    Player player = (Player) ctx.getSource();
                    boolean nowEnabled = plugin.getStaffChatManager().toggle(player.getUniqueId());

                    Component result;
                    if (nowEnabled) {
                        result = Component.text("Staff chat enabled.", NamedTextColor.GREEN);
                    } else {
                        result = Component.text("Staff chat disabled.", NamedTextColor.RED);
                    }
                    plugin.getChatBridge().syncStaffState(player, nowEnabled);
                    plugin.getMessageManager().deliver(player, result);
                    return 1;
                })
                .build();

        BrigadierCommand command = new BrigadierCommand(node);
        plugin.getServer().getCommandManager().register(
                plugin.getServer().getCommandManager().metaBuilder(command)
                        .plugin(plugin)
                        .build(),
                command
        );
    }
}
