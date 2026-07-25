package crabcraft.net.crabUtilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.HoverEvent.ShowEntity;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NicknameMessageListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Component NO_BACKEND_LIFECYCLE_MESSAGE = Component.empty();
    private final NicknameResolver nicknameResolver;

    public NicknameMessageListener(CrabUtilities plugin) {
        this(new NicknameResolver() {
            @Override
            public ResolvedNickname byUuid(UUID uuid) {
                return resolve(Bukkit.getPlayer(uuid));
            }

            @Override
            public ResolvedNickname byAccountName(String accountName) {
                return resolve(Bukkit.getPlayerExact(accountName));
            }

            private ResolvedNickname resolve(Player player) {
                if (player == null) return null;
                Component nickname = NicknameComponentResolver.forPlayer(plugin.getEssentials(), player);
                return nickname == null ? null : new ResolvedNickname(player.getName(), nickname);
            }
        });
    }

    NicknameMessageListener(NicknameResolver nicknameResolver) {
        this.nicknameResolver = nicknameResolver;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Component msg = event.deathMessage();
        if (msg != null) {
            event.deathMessage(transformComponent(msg));
        }
        Component deathScreen = event.deathScreenMessageOverride();
        if (deathScreen != null) {
            event.deathScreenMessageOverride(transformComponent(deathScreen));
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        // Velocity owns lifecycle broadcasts after its authoritative nickname seed completes.
        event.joinMessage(NO_BACKEND_LIFECYCLE_MESSAGE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(NO_BACKEND_LIFECYCLE_MESSAGE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        event.leaveMessage(NO_BACKEND_LIFECYCLE_MESSAGE);
    }

    Component transformComponent(Component input) {
        ResolvedNickname player = resolvePlayer(input);
        if (player != null) {
            // A SHOW_ENTITY wrapper and its children are one semantic player name. Replacing
            // it before recursion prevents both the wrapper and nested account name from firing.
            return replacePlayerComponent(input, player);
        }

        Component afterChildren = input;

        if (!input.children().isEmpty()) {
            List<Component> newChildren = new ArrayList<>(input.children().size());
            for (Component child : input.children()) {
                newChildren.add(transformComponent(child));
            }
            afterChildren = input.children(newChildren);
        }

        if (afterChildren instanceof TranslatableComponent tc) {
            List<ComponentLike> newArgs = new ArrayList<>(tc.arguments().size());
            for (TranslationArgument arg : tc.arguments()) {
                Object value = arg.value();
                if (!(value instanceof ComponentLike like)) {
                    newArgs.add(arg);
                    continue;
                }

                newArgs.add(TranslationArgument.component(transformComponent(like.asComponent())));
            }
            afterChildren = tc.toBuilder().arguments(newArgs).build();
        }

        return afterChildren;
    }

    private ResolvedNickname resolvePlayer(Component component) {
        HoverEvent<?> hover = component.style().hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ENTITY) {
            ShowEntity show = (ShowEntity) hover.value();
            ResolvedNickname player = nicknameResolver.byUuid(show.id());
            if (player != null) return player;
        }

        if (component instanceof TextComponent tc) {
            String content = tc.content();
            if (!content.isEmpty()) return nicknameResolver.byAccountName(content);
        }

        return null;
    }

    private Component replacePlayerComponent(Component original, ResolvedNickname player) {
        List<Component> children = original.children();
        NameSpan nameSpan = findNameSpan(children, player.accountName());
        boolean accountNameAtRoot = original instanceof TextComponent text
                && text.content().equals(player.accountName());

        if (accountNameAtRoot || nameSpan != null) {
            List<Component> replacedChildren = new ArrayList<>(children.size() + 1);
            if (nameSpan == null) {
                replacedChildren.add(player.nickname());
                replacedChildren.addAll(children);
            } else {
                replacedChildren.addAll(children.subList(0, nameSpan.start()));
                replacedChildren.add(player.nickname());
                replacedChildren.addAll(children.subList(nameSpan.end(), children.size()));
            }

            if (accountNameAtRoot) {
                return ((TextComponent) original).content("").children(replacedChildren);
            }
            return original.children(replacedChildren);
        }

        if (PLAIN.serialize(original).equals(player.accountName())) {
            return Component.empty().style(original.style()).append(player.nickname());
        }

        // No account-name subtree means this wrapper was already transformed.
        return original;
    }

    private static NameSpan findNameSpan(List<Component> children, String accountName) {
        for (int i = 0; i < children.size(); i++) {
            if (PLAIN.serialize(children.get(i)).equals(accountName)) {
                return new NameSpan(i, i + 1);
            }
        }

        for (int start = 0; start < children.size(); start++) {
            StringBuilder candidate = new StringBuilder();
            for (int end = start; end < children.size(); end++) {
                candidate.append(PLAIN.serialize(children.get(end)));
                String text = candidate.toString();
                if (text.equals(accountName)) return new NameSpan(start, end + 1);
                if (!accountName.startsWith(text)) break;
            }
        }
        return null;
    }

    interface NicknameResolver {
        ResolvedNickname byUuid(UUID uuid);

        ResolvedNickname byAccountName(String accountName);
    }

    record ResolvedNickname(String accountName, Component nickname) {
    }

    private record NameSpan(int start, int end) {
    }
}
