package crabcraft.net.crabUtilities.chat

import crabcraft.net.crabUtilities.NicknameComponentResolver
import crabcraft.net.crabUtilities.VanishStatus
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Highlights mention tokens resolving to online local players and records who to ping. Replacements preserve the
 * component tree and bind names without interpreting tags.
 */
open class MentionProcessor(
    private val enabled: Boolean,
    private val prefix: String,
    private val highlightFormat: String,
    private val miniMessage: MiniMessage,
    private val essentialsPlugin: Plugin?,
) {
    data class Result(private val message: Component, private val mentioned: Set<UUID>) {
        fun message(): Component = message

        fun mentioned(): Set<UUID> = mentioned
    }

    data class MentionIdentity(val uuid: UUID, val username: String, val nickname: String?) {
        fun uuid(): UUID = uuid

        fun username(): String = username

        fun nickname(): String? = nickname
    }

    data class MentionTarget(val uuid: UUID, val username: String) {
        fun uuid(): UUID = uuid

        fun username(): String = username
    }

    data class AliasIndex(
        private val targets: Map<String, MentionTarget>,
        private val aliases: List<String>,
        private val completionNames: Map<UUID, String>,
    ) {
        fun targets(): Map<String, MentionTarget> = targets

        fun aliases(): List<String> = aliases

        fun completionNames(): Map<UUID, String> = completionNames

        fun target(alias: String): MentionTarget? = targets[normalize(alias)]
    }

    open fun process(input: Component, senderUuid: UUID): Result {
        if (!enabled) return Result(input, HashSet())
        val aliases = aliasIndex(Bukkit.getOnlinePlayers(), essentialsPlugin)
        if (aliases.aliases().isEmpty()) return Result(input, HashSet())
        val mentioned = HashSet<UUID>()
        val message = input.replaceText { config ->
            config.match(mentionPattern(prefix, aliases.aliases())).replacement { match, builder ->
                val target = aliases.target(match.group(1))
                if (target == null) builder
                else {
                    if (target.uuid != senderUuid) mentioned.add(target.uuid)
                    miniMessage
                        .deserialize(highlightFormat, Placeholder.unparsed("name", match.group()))
                        .clickEvent(ClickEvent.suggestCommand("/msg ${target.username} "))
                }
            }
        }
        return Result(message, mentioned)
    }

    companion object {
        @JvmStatic
        fun aliasIndex(players: Iterable<Player>, essentialsPlugin: Plugin?): AliasIndex {
            val plain = PlainTextComponentSerializer.plainText()
            val identities = ArrayList<MentionIdentity>()
            for (player in players) {
                if (VanishStatus.isVanished(essentialsPlugin, player)) continue
                val nickname = NicknameComponentResolver.forPlayer(essentialsPlugin, player)
                val nicknameText = nickname?.let { plain.serialize(it).trim { c -> c <= ' ' } }
                identities.add(MentionIdentity(player.uniqueId, player.name, nicknameText?.takeIf { it.isNotEmpty() }))
            }
            return buildAliasIndex(identities)
        }

        @JvmStatic
        fun buildAliasIndex(identities: List<MentionIdentity>): AliasIndex {
            val canonicalTargets = LinkedHashMap<String, MentionTarget>()
            val aliasSpellings = LinkedHashMap<String, String>()
            for (identity in identities) {
                val key = normalize(identity.username)
                canonicalTargets[key] = MentionTarget(identity.uuid, identity.username)
                aliasSpellings.putIfAbsent(key, identity.username)
            }
            val targets = LinkedHashMap(canonicalTargets)
            val ambiguous = LinkedHashSet<String>()
            for (identity in identities) {
                val nickname = cleanNickname(identity.nickname) ?: continue
                val key = normalize(nickname)
                aliasSpellings.putIfAbsent(key, nickname)
                if (key in canonicalTargets || key in ambiguous) continue
                val existing = targets[key]
                if (existing == null) {
                    targets[key] = MentionTarget(identity.uuid, identity.username)
                } else if (existing.uuid != identity.uuid) {
                    targets.remove(key)
                    ambiguous.add(key)
                }
            }
            val completionNames = LinkedHashMap<UUID, String>()
            for (identity in identities) {
                val nickname = cleanNickname(identity.nickname)
                val target = nickname?.let { targets[normalize(it)] }
                completionNames[identity.uuid] =
                    if (target != null && target.uuid == identity.uuid) nickname else identity.username
            }
            return AliasIndex(
                Collections.unmodifiableMap(LinkedHashMap(targets)),
                java.util.List.copyOf(aliasSpellings.values),
                Collections.unmodifiableMap(completionNames),
            )
        }

        @JvmStatic
        fun mentionPattern(prefix: String, aliases: List<String>): Pattern {
            val alternatives = aliases.sortedByDescending { it.length }.joinToString("|") { Pattern.quote(it) }
            return Pattern.compile(
                "(?<![\\p{L}\\p{M}\\p{N}_-])" +
                    Pattern.quote(prefix) +
                    "(" +
                    alternatives +
                    ")(?![\\p{L}\\p{M}\\p{N}_-])",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )
        }

        private fun cleanNickname(nickname: String?): String? = nickname?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() }

        private fun normalize(alias: String): String = alias.lowercase(Locale.ROOT)
    }
}
