package crabcraft.net.crabUtilities.velocity.restrictedarea;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import crabcraft.net.crabUtilities.restrictedarea.VerificationReminder;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Prevents proxy-owned actions from bypassing the Paper holding area. */
public final class RestrictedAreaProxyListener {

    private final VerificationReminder reminder = new VerificationReminder();
    private final BooleanSupplier enabled;
    private final Supplier<String> permission;

    public RestrictedAreaProxyListener(final CrabUtilitiesVelocity plugin) {
        this(
                () -> plugin.getConfig().isRestrictedAreaEnabled(),
                () -> plugin.getConfig().getRestrictedAreaBypassPermission());
    }

    RestrictedAreaProxyListener(
            final BooleanSupplier enabled,
            final Supplier<String> permission
    ) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
        this.permission = Objects.requireNonNull(permission, "permission");
    }

    @Subscribe(order = PostOrder.LAST)
    public void onCommand(final CommandExecuteEvent event) {
        if (event.getCommandSource() instanceof Player player && isRestricted(player)) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            reminder.send(player.getUniqueId(), player);
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onChat(final PlayerChatEvent event) {
        if (isRestricted(event.getPlayer())) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            reminder.send(event.getPlayer().getUniqueId(), event.getPlayer());
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServerPreConnect(final ServerPreConnectEvent event) {
        if (event.getPreviousServer() != null && isRestricted(event.getPlayer())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            reminder.send(event.getPlayer().getUniqueId(), event.getPlayer());
        }
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        reminder.forget(event.getPlayer().getUniqueId());
    }

    private boolean isRestricted(final Player player) {
        return enabled.getAsBoolean() && !player.hasPermission(permission.get());
    }
}
