package crabcraft.net.crabUtilities.velocity.messaging;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class PlayerLookup {

    private PlayerLookup() {}

    public static Optional<Player> resolve(CrabUtilitiesVelocity plugin, String name) {
        Optional<Player> byUsername = plugin.getServer().getPlayer(name);
        if (byUsername.isPresent()) return byUsername;

        return uniqueNicknameMatch(
                plugin.getServer().getAllPlayers(),
                player -> plugin.getNicknameCache().getPlainNickname(player.getUniqueId()),
                name);
    }

    public static SuggestionProvider<CommandSource> playerSuggestions(CrabUtilitiesVelocity plugin) {
        return (ctx, builder) -> {
            String input = suggestionPrefix(builder.getRemainingLowerCase());
            UUID selfId = ctx.getSource() instanceof Player p ? p.getUniqueId() : null;
            Map<String, Integer> nicknameCounts = new HashMap<>();

            for (Player player : plugin.getServer().getAllPlayers()) {
                String plain = plugin.getNicknameCache().getPlainNickname(player.getUniqueId());
                if (plain != null && !plain.isBlank()) {
                    nicknameCounts.merge(plain.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }

            for (Player player : plugin.getServer().getAllPlayers()) {
                if (player.getUniqueId().equals(selfId)) continue;

                // Show a single name per player: the nickname when set,
                // otherwise the real username. Ambiguous nicknames and nicks
                // shadowed by a real username are not valid lookup targets.
                String plain = plugin.getNicknameCache().getPlainNickname(player.getUniqueId());
                boolean usableNickname = plain != null
                        && !plain.isBlank()
                        && nicknameCounts.getOrDefault(plain.toLowerCase(Locale.ROOT), 0) == 1
                        && plugin.getServer().getPlayer(plain)
                                .map(byUsername -> byUsername.getUniqueId().equals(player.getUniqueId()))
                                .orElse(true);
                String display = usableNickname ? plain : player.getUsername();
                if (display.toLowerCase(Locale.ROOT).startsWith(input)) {
                    builder.suggest(StringArgumentType.escapeIfRequired(display));
                }
            }
            return builder.buildFuture();
        };
    }

    static <T> Optional<T> uniqueNicknameMatch(Iterable<T> candidates,
                                                Function<T, String> nickname,
                                                String name) {
        T match = null;
        for (T candidate : candidates) {
            String plain = nickname.apply(candidate);
            if (plain == null || !plain.equalsIgnoreCase(name)) continue;
            if (match != null) return Optional.empty();
            match = candidate;
        }
        return Optional.ofNullable(match);
    }

    static String suggestionPrefix(String input) {
        return input.startsWith("\"") ? input.substring(1) : input;
    }
}
