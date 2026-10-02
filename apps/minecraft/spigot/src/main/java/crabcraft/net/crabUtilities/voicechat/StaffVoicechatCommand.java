package crabcraft.net.crabUtilities.voicechat;

import crabcraft.net.crabUtilities.CrabMessages;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class StaffVoicechatCommand implements CommandExecutor, TabCompleter {

    static final String PERMISSION = "crabutilities.staffvc";
    static final UUID GROUP_ID = CrabVoicechatPlugin.deterministicGroupId("Staff VC");
    private final Supplier<VoicechatServerApi> apiSupplier;

    StaffVoicechatCommand(Supplier<VoicechatServerApi> apiSupplier) {
        this.apiSupplier = apiSupplier;
    }

    static Group createGroup(VoicechatServerApi api) {
        return api.groupBuilder()
                .setId(GROUP_ID)
                .setName("Staff VC")
                .setType(Group.Type.ISOLATED)
                .setHidden(true)
                .setPersistent(true)
                .build();
    }

    static boolean canJoin(Player player, UUID groupId) {
        return !GROUP_ID.equals(groupId) || player.hasPermission(PERMISSION);
    }

    static boolean evictUnauthorisedMember(VoicechatServerApi api, Player player) {
        VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
        Group group = connection == null ? null : connection.getGroup();
        if (group == null || canJoin(player, group.getId())) return false;

        connection.setGroup(null);
        VoicechatConnection updated = api.getConnectionOf(player.getUniqueId());
        Group committed = updated == null ? null : updated.getGroup();
        if (updated == null || committed != null && GROUP_ID.equals(committed.getId())) return false;

        player.sendMessage(CrabMessages.error("Removed from staff voice chat because you no longer have permission."));
        return true;
    }

    static void guardGroupEntry(JoinGroupEvent event) {
        Group group = event.getGroup();
        if (event.isCancelled() || group == null || !GROUP_ID.equals(group.getId())) return;
        VoicechatConnection connection = event.getConnection();
        Object player = connection == null ? null : connection.getPlayer().getPlayer();
        if (player instanceof Player bukkitPlayer && canJoin(bukkitPlayer, group.getId())) return;
        event.cancel();
        if (player instanceof Player bukkitPlayer) {
            bukkitPlayer.sendMessage(CrabMessages.error("You do not have permission to join staff voice chat."));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(CrabMessages.error("You do not have permission to use staff voice chat."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(CrabMessages.error("Use /staffvc in-game to join staff voice chat."));
            return true;
        }
        if (args.length != 0) return false;

        VoicechatServerApi api = apiSupplier.get();
        if (api == null) {
            player.sendMessage(CrabMessages.error("Staff voice chat is unavailable on this server."));
            return true;
        }
        VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
        if (connection == null || !connection.isConnected()) {
            player.sendMessage(CrabMessages.error("Connect to Simple Voice Chat before using /staffvc."));
            return true;
        }

        Group current = connection.getGroup();
        boolean leaving = current != null && GROUP_ID.equals(current.getId());
        Group target = leaving ? null : api.getGroup(GROUP_ID);
        if (!leaving && target == null) {
            player.sendMessage(CrabMessages.error("Staff voice chat is unavailable on this server."));
            return true;
        }
        connection.setGroup(target);

        // SVC connection objects are snapshots; re-fetch after the cancellable join/leave.
        VoicechatConnection updated = api.getConnectionOf(player.getUniqueId());
        Group committed = updated == null ? null : updated.getGroup();
        if (updated == null || !Objects.equals(leaving ? null : GROUP_ID,
                committed == null ? null : committed.getId())) {
            player.sendMessage(CrabMessages.error("Staff voice chat could not be changed. Please try again."));
            return true;
        }
        player.sendMessage(CrabMessages.success(leaving
                ? "Left staff voice chat."
                : "Joined staff voice chat. Use /staffvc again to leave."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
