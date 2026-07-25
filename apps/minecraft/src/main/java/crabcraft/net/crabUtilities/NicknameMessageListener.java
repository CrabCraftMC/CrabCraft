package crabcraft.net.crabUtilities;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.HoverEvent.ShowEntity;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NicknameMessageListener implements Listener {
    private final CrabUtilities plugin;
    private final LegacyComponentSerializer legacy;
    private final MiniMessage mm;

    private static final Pattern AMP_HEX_PATTERN = Pattern.compile("&[#]([0-9a-fA-F]{6})");

    public NicknameMessageListener(CrabUtilities plugin) {
        this.plugin = plugin;
        // Legacy serializer supporting &-codes and hex (both &#RRGGBB and &x&R&R&G&G&B&B)
        this.legacy = LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();
        this.mm = MiniMessage.miniMessage();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Component msg = event.deathMessage();
        if (msg == null) return;
        Component replaced = transformComponent(msg);
        if (replaced != null) {
            event.deathMessage(replaced);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Component msg = event.message();
        if (msg == null) return;
        Component replaced = transformComponent(msg);
        if (replaced != null) {
            event.message(replaced);
        }
    }

    private Component transformComponent(Component input) {
        // Rebuild recursively, replacing any player-entity components with their nick component
        Component afterChildren = input;

        // First, transform children
        if (!input.children().isEmpty()) {
            List<Component> newChildren = new ArrayList<>(input.children().size());
            for (Component child : input.children()) {
                newChildren.add(transformComponent(child));
            }
            afterChildren = input.children(newChildren);
        }

        // Then, handle translatable arguments (death/advancement messages are translatable)
        if (afterChildren instanceof TranslatableComponent tc) {
            List<Component> newArgs = new ArrayList<>(tc.args().size());
            for (final net.kyori.adventure.text.ComponentLike like : tc.args()) {
                Component argComp = like.asComponent();
                argComp = transformComponent(argComp); // recurse in args too
                argComp = maybeReplacePlayerNameComponent(argComp);
                newArgs.add(argComp);
            }
            afterChildren = tc.toBuilder().args(newArgs).build();
        }

        // Finally, try replacing this exact component if it matches a player name component
        return maybeReplacePlayerNameComponent(afterChildren);
    }

    private Component maybeReplacePlayerNameComponent(Component component) {
        // Detect player components via hover show_entity containing a player UUID
        Style style = component.style();
        HoverEvent<?> hover = style.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ENTITY) {
            ShowEntity show = (ShowEntity) hover.value();
            UUID id = show.id();
            if (id != null) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    Component nick = nicknameFor(p);
                    if (nick != null) {
                        // Preserve hover/click/insertion/font from original component, but let nickname provide its own colors/formatting
                        ClickEvent click = style.clickEvent();
                        String insertion = style.insertion();
                        var font = style.font();

                        Component out = nick;
                        if (hover != null) out = out.hoverEvent(hover);
                        if (click != null) out = out.clickEvent(click);
                        if (insertion != null) out = out.insertion(insertion);
                        if (font != null) out = out.font(font);

                        // Do NOT append original children, as they may include per-character colored text (causing duplicates)
                        return out;
                    }
                }
            }
        }

        // As a fallback, if it's a plain text component exactly equal to an online player's name, replace it
        if (component instanceof TextComponent tc) {
            String content = tc.content();
            if (!content.isEmpty()) {
                Player p = Bukkit.getPlayerExact(content);
                if (p != null) {
                    Component nick = nicknameFor(p);
                    if (nick != null) {
                        Component out = nick;
                        HoverEvent<?> hv = component.style().hoverEvent();
                        if (hv != null) out = out.hoverEvent(hv);
                        ClickEvent click = component.style().clickEvent();
                        if (click != null) out = out.clickEvent(click);
                        String insertion = component.style().insertion();
                        if (insertion != null) out = out.insertion(insertion);
                        var font = component.style().font();
                        if (font != null) out = out.font(font);
                        // Do NOT append original children to avoid duplicating the name
                        return out;
                    }
                }
            }
        }

        return component;
    }

    private Component nicknameFor(Player player) {
        Plugin ess = plugin.getEssentials();
        if (!(ess instanceof Essentials essentials)) return null;
        User user = essentials.getUser(player);
        if (user == null) return null;
        String raw = user.getNickname();
        if (raw == null || raw.isBlank()) return null;

        // 1) If the nickname looks like MiniMessage (e.g., contains <#RRGGBB> or gradient tags), parse with MiniMessage
        if (looksLikeMiniMessage(raw)) {
            try {
                return mm.deserialize(raw);
            } catch (Exception ignored) {
                // Fall through to legacy if MiniMessage parsing fails
            }
        }

        // 2) Fallback: legacy parsing with support for hex (&#RRGGBB and &x&... and §x§...)
        String processed = raw
                .replace('§', '&'); // normalize section sign to & for the serializer
        processed = convertAmpersandHex(processed);
        try {
            return legacy.deserialize(processed);
        } catch (Exception ex) {
            // Final fallback to plain text
            return Component.text(raw);
        }
    }

    private static boolean looksLikeMiniMessage(String s) {
        // Heuristic: contains <#RRGGBB> or <gradient:...> or other typical minimessage color tags
        return s.contains("<#") || s.contains("</gradient>") || s.contains("<gradient:") || s.contains("<rainbow>") || s.contains("</rainbow>");
    }

    private static String convertAmpersandHex(String input) {
        // Replace occurrences of &#RRGGBB with &x&R&R&G&G&B&B so our serializer can parse it
        StringBuffer sb = new StringBuffer();
        Matcher m = AMP_HEX_PATTERN.matcher(input);
        while (m.find()) {
            String hex = m.group(1).toUpperCase(Locale.ROOT);
            String replacement = "&x&" + hex.charAt(0) + "&" + hex.charAt(1)
                    + "&" + hex.charAt(2) + "&" + hex.charAt(3)
                    + "&" + hex.charAt(4) + "&" + hex.charAt(5);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
