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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
    private final String highlightFormat;
    private final MiniMessage miniMessage;
    private final Pattern pattern;
    private final Plugin essentialsPlugin;

    public MentionProcessor(boolean enabled, String prefix, String highlightFormat,
                            MiniMessage miniMessage, Plugin essentialsPlugin) {
        this.enabled = enabled;
        this.highlightFormat = highlightFormat;
        this.miniMessage = miniMessage;
        this.essentialsPlugin = essentialsPlugin;
        // \B before the (quoted) prefix so it isn't part of a longer word,
        // then capture a run of word chars as the name, ending on a word
        // boundary. Case-insensitive so @steve matches Steve.
        this.pattern = Pattern.compile(
                "\\B" + Pattern.quote(prefix) + "(\\w+)\\b",
                Pattern.CASE_INSENSITIVE);
    }

    /**
     * Holds the assembled message component and the set of local players to
     * ping (the sender is always excluded).
     */
    public record Result(Component message, Set<UUID> mentioned) {}

    /**
     * Builds the message component and the mention set. When mentions are
     * disabled the supplied component is returned unchanged.
     */
    public Result process(Component input, UUID senderUuid) {
        if (!enabled) {
            return new Result(input, new HashSet<>());
        }

        Set<UUID> mentioned = new HashSet<>();
        Component message = input.replaceText(config -> config
                .match(pattern)
                .replacement((match, builder) -> {
                    String token = match.group();   // includes the prefix, e.g. "@Steve"
                    String name = match.group(1);   // captured word, e.g. "Steve"
                    Player target = matchOnline(name);
                    if (target != null) {
                        if (!target.getUniqueId().equals(senderUuid)) {
                            mentioned.add(target.getUniqueId());
                        }
                        return miniMessage.deserialize(
                                highlightFormat, Placeholder.unparsed("name", token))
                                .clickEvent(ClickEvent.suggestCommand(messageCommand(target.getName())));
                    }
                    return builder;
                }));
        return new Result(message, mentioned);
    }

    /**
     * Resolves a captured name to an online local player by account name or
     * the plain text of their display name (covers nicks), case-insensitively.
     */
    private Player matchOnline(String name) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
            Component nickname = NicknameComponentResolver.forPlayer(essentialsPlugin, p);
            if (nickname != null && PlainTextComponentSerializer.plainText().serialize(nickname).equalsIgnoreCase(name)) {
                return p;
            }
            String display = PlainTextComponentSerializer.plainText().serialize(p.displayName());
            if (display.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private static String messageCommand(String username) {
        return "/msg " + username + " ";
    }
}
