package crabcraft.net.crabUtilities.velocity.messaging;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;

import java.util.Optional;
import java.util.UUID;

public final class PlayerLookup {

    private PlayerLookup() {}

    public static Optional<Player> resolve(CrabUtilitiesVelocity plugin, String name) {
        Optional<Player> byUsername = plugin.getServer().getPlayer(name);
        if (byUsername.isPresent()) return byUsername;

        for (Player player : plugin.getServer().getAllPlayers()) {
            String plain = plugin.getNicknameCache().getPlainNickname(player.getUniqueId());
            if (plain != null && plain.equalsIgnoreCase(name)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    public static SuggestionProvider<CommandSource> playerSuggestions(CrabUtilitiesVelocity plugin) {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            UUID selfId = ctx.getSource() instanceof Player p ? p.getUniqueId() : null;

            for (Player player : plugin.getServer().getAllPlayers()) {
                if (player.getUniqueId().equals(selfId)) continue;

                // Show a single name per player: the nickname when set,
                // otherwise the real username. Typing the real username still
                // resolves via resolve(), it just isn't suggested separately.
                String plain = plugin.getNicknameCache().getPlainNickname(player.getUniqueId());
                String display = (plain != null && !plain.isBlank()) ? plain : player.getUsername();
                if (display.toLowerCase().startsWith(input)) {
                    builder.suggest(display);
                }
            }
            return builder.buildFuture();
        };
    }
}
