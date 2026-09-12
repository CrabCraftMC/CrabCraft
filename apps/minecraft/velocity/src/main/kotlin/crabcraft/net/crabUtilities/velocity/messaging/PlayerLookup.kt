package crabcraft.net.crabUtilities.velocity.messaging

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import crabcraft.net.crabUtilities.velocity.CrabUtilitiesVelocity
import java.util.Locale
import java.util.Optional
import java.util.function.Function

class PlayerLookup private constructor() {
    companion object {
        @JvmStatic fun resolve(plugin: CrabUtilitiesVelocity, name: String): Optional<Player> {
            val byUsername = plugin.getServer().getPlayer(name)
            if (byUsername.isPresent) return byUsername
            return uniqueNicknameMatch(plugin.getServer().allPlayers, Function { player -> plugin.getNicknameCache().getPlainNickname(player.uniqueId) }, name)
        }
        @JvmStatic fun playerSuggestions(plugin: CrabUtilitiesVelocity): SuggestionProvider<CommandSource> = SuggestionProvider { ctx, builder ->
            val input = suggestionPrefix(builder.remainingLowerCase)
            val selfId = (ctx.source as? Player)?.uniqueId
            val nicknameCounts = HashMap<String, Int>()
            for (player in plugin.getServer().allPlayers) {
                val plain = plugin.getNicknameCache().getPlainNickname(player.uniqueId)
                if (!plain.isNullOrBlank()) nicknameCounts.merge(plain.lowercase(Locale.ROOT), 1, Int::plus)
            }
            for (player in plugin.getServer().allPlayers) {
                if (player.uniqueId == selfId) continue
                // Ambiguous nicknames and those shadowing another username are not lookup targets.
                val plain = plugin.getNicknameCache().getPlainNickname(player.uniqueId)
                val usableNickname = !plain.isNullOrBlank() && nicknameCounts.getOrDefault(plain.lowercase(Locale.ROOT), 0) == 1
                    && plugin.getServer().getPlayer(plain).map { byUsername -> byUsername.uniqueId == player.uniqueId }.orElse(true)
                val display = if (usableNickname) plain!! else player.username
                if (display.lowercase(Locale.ROOT).startsWith(input)) builder.suggest(StringArgumentType.escapeIfRequired(display))
            }
            builder.buildFuture()
        }
        @JvmStatic fun <T : Any> uniqueNicknameMatch(candidates: Iterable<T>, nickname: Function<T, String?>, name: String): Optional<T> {
            var match: T? = null
            for (candidate in candidates) {
                val plain = nickname.apply(candidate)
                if (plain == null || !plain.equals(name, ignoreCase = true)) continue
                if (match != null) return Optional.empty()
                match = candidate
            }
            return Optional.ofNullable(match)
        }
        @JvmStatic fun suggestionPrefix(input: String): String = if (input.startsWith("\"")) input.substring(1) else input
    }
}
