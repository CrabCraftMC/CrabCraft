package crabcraft.net.crabUtilities.chat;

import crabcraft.net.crabUtilities.NicknameComponentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scans a chat component for {@code <prefix>name} mention tokens
 * (e.g. {@code @Steve}), highlighting any that resolve to an online local
 * player and recording who to ping.
 *
 * <p>The incoming component may contain safe player-selected colours and
 * decorations. Mention replacements preserve the surrounding component tree;
 * only the configured highlight is deserialized, with the matched token bound
 * through {@link Placeholder#unparsed} so a name can't inject tags.
 */
public class MentionProcessor {

    private final boolean enabled;
    private final String prefix;
    private final String highlightFormat;
    private final MiniMessage miniMessage;
    private final Plugin essentialsPlugin;

    public MentionProcessor(boolean enabled, String prefix, String highlightFormat,
                            MiniMessage miniMessage, Plugin essentialsPlugin) {
        this.enabled = enabled;
        this.prefix = prefix;
        this.highlightFormat = highlightFormat;
        this.miniMessage = miniMessage;
        this.essentialsPlugin = essentialsPlugin;
    }

    /**
     * Holds the assembled message component and the set of local players to
     * ping (the sender is always excluded).
     */
    public record Result(Component message, Set<UUID> mentioned) {}

    record MentionIdentity(UUID uuid, String username, String nickname) {}

    record MentionTarget(UUID uuid, String username) {}

    record AliasIndex(Map<String, MentionTarget> targets, List<String> aliases,
                      Map<UUID, String> completionNames) {
        MentionTarget target(String alias) {
            return targets.get(normalize(alias));
        }
    }

    /**
     * Builds the message component and the mention set. When mentions are
     * disabled the supplied component is returned unchanged.
     */
    public Result process(Component input, UUID senderUuid) {
        if (!enabled) {
            return new Result(input, new HashSet<>());
        }

        AliasIndex aliases = aliasIndex(Bukkit.getOnlinePlayers(), essentialsPlugin);
        if (aliases.aliases().isEmpty()) {
            return new Result(input, new HashSet<>());
        }

        Set<UUID> mentioned = new HashSet<>();
        Pattern mentionPattern = mentionPattern(prefix, aliases.aliases());
        Component message = input.replaceText(config -> config
                .match(mentionPattern)
                .replacement((match, builder) -> {
                    String token = match.group();   // includes the prefix, e.g. "@Steve"
                    String name = match.group(1);
                    MentionTarget target = aliases.target(name);
                    if (target != null) {
                        if (!target.uuid().equals(senderUuid)) {
                            mentioned.add(target.uuid());
                        }
                        return miniMessage.deserialize(
                                highlightFormat, Placeholder.unparsed("name", token))
                                .clickEvent(ClickEvent.suggestCommand(messageCommand(target.username())));
                    }
                    return builder;
                }));
        return new Result(message, mentioned);
    }

    static AliasIndex aliasIndex(Iterable<? extends Player> players, Plugin essentialsPlugin) {
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        List<MentionIdentity> identities = new ArrayList<>();
        for (Player player : players) {
            Component nickname = NicknameComponentResolver.forPlayer(essentialsPlugin, player);
            String nicknameText = nickname == null ? null : plain.serialize(nickname).trim();
            identities.add(new MentionIdentity(
                    player.getUniqueId(), player.getName(),
                    nicknameText == null || nicknameText.isEmpty() ? null : nicknameText));
        }
        return buildAliasIndex(identities);
    }

    static AliasIndex buildAliasIndex(List<MentionIdentity> identities) {
        Map<String, MentionTarget> canonicalTargets = new LinkedHashMap<>();
        Map<String, String> aliasSpellings = new LinkedHashMap<>();
        for (MentionIdentity identity : identities) {
            String key = normalize(identity.username());
            canonicalTargets.put(key, new MentionTarget(identity.uuid(), identity.username()));
            aliasSpellings.putIfAbsent(key, identity.username());
        }

        Map<String, MentionTarget> targets = new LinkedHashMap<>(canonicalTargets);
        Set<String> ambiguous = new LinkedHashSet<>();
        for (MentionIdentity identity : identities) {
            String nickname = cleanNickname(identity.nickname());
            if (nickname == null) continue;

            String key = normalize(nickname);
            aliasSpellings.putIfAbsent(key, nickname);
            if (canonicalTargets.containsKey(key) || ambiguous.contains(key)) continue;

            MentionTarget existing = targets.get(key);
            if (existing == null) {
                targets.put(key, new MentionTarget(identity.uuid(), identity.username()));
            } else if (!existing.uuid().equals(identity.uuid())) {
                targets.remove(key);
                ambiguous.add(key);
            }
        }

        Map<UUID, String> completionNames = new LinkedHashMap<>();
        for (MentionIdentity identity : identities) {
            String nickname = cleanNickname(identity.nickname());
            MentionTarget target = nickname == null ? null : targets.get(normalize(nickname));
            completionNames.put(identity.uuid(), target != null && target.uuid().equals(identity.uuid())
                    ? nickname : identity.username());
        }

        return new AliasIndex(
                Collections.unmodifiableMap(new LinkedHashMap<>(targets)),
                List.copyOf(aliasSpellings.values()),
                Collections.unmodifiableMap(completionNames));
    }

    static Pattern mentionPattern(String prefix, List<String> aliases) {
        String alternatives = aliases.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile(
                "(?<![\\p{L}\\p{M}\\p{N}_-])" + Pattern.quote(prefix)
                        + "(" + alternatives + ")(?![\\p{L}\\p{M}\\p{N}_-])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String cleanNickname(String nickname) {
        if (nickname == null) return null;
        String cleaned = nickname.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String normalize(String alias) {
        return alias.toLowerCase(Locale.ROOT);
    }

    private static String messageCommand(String username) {
        return "/msg " + username + " ";
    }
}
