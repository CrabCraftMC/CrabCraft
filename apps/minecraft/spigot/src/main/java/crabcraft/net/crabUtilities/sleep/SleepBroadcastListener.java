package crabcraft.net.crabUtilities.sleep;

import crabcraft.net.crabUtilities.CrabUtilities;
import crabcraft.net.crabUtilities.CrabMessages;
import crabcraft.net.crabUtilities.NicknameComponentResolver;
import crabcraft.net.crabUtilities.PlayerVisibility;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.TimeSkipEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Announces in chat which player(s) slept to skip the night.
 *
 * <p>Opt-in and disabled by default; the config is read live on every event so
 * {@code /crabutilities reload} takes effect without re-registration.
 *
 * <p>Detection hangs off {@link TimeSkipEvent} with reason
 * {@link TimeSkipEvent.SkipReason#NIGHT_SKIP}. Vanilla fires that event, per
 * world, the moment enough players are asleep for the night to be skipped —
 * honouring the {@code playersSleepingPercentage} game rule for us — and it
 * fires <em>before</em> the sleepers are woken, so {@link Player#isSleeping()}
 * still reports who was in bed. We simply collect the sleeping players in the
 * affected world and format the announcement from them.
 *
 * <p>Player names fill MiniMessage component placeholders so styled nicknames
 * are preserved without parsing nickname text as MiniMessage tags.
 */
public class SleepBroadcastListener implements Listener {

    // Uses the shared pastel highlight and off-white body colours.
    private static final String DEFAULT_SINGLE_FORMAT =
            CrabMessages.HIGHLIGHT_TAG + "<player>"
                    + CrabMessages.TEXT_TAG + " slept to skip the night.";
    private static final String DEFAULT_MULTIPLE_FORMAT =
            CrabMessages.HIGHLIGHT_TAG + "<players>"
                    + CrabMessages.TEXT_TAG + " slept to skip the night.";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * {@code TimeSkipEvent#getSkipReason()}, resolved reflectively — or
     * {@code null} when this server doesn't have it. The method has been in the
     * Bukkit API since 1.14, but at least one fork ships it with a different
     * return type than the API we compile against, and a direct call then
     * throws {@link NoSuchMethodError}: the JVM links an invocation by its full
     * descriptor, return type included, while reflection matches by name and
     * parameters only. So we must never emit the descriptor — invoke the
     * {@link Method} reflectively and compare the returned constant's name.
     * When the reason can't be resolved at all, the "is anyone actually
     * sleeping?" guard below rejects non-sleep skips (commands, other plugins)
     * instead.
     */
    private static final Method GET_SKIP_REASON = resolveGetSkipReason();

    private final CrabUtilities plugin;

    public SleepBroadcastListener(CrabUtilities plugin) {
        this.plugin = plugin;
    }

    private static Method resolveGetSkipReason() {
        try {
            return TimeSkipEvent.class.getMethod("getSkipReason");
        } catch (NoSuchMethodException | LinkageError e) {
            return null;
        }
    }

    /**
     * True only when the skip reason is resolvable and is something other than
     * a night skip. Unresolvable (missing method, reflective failure, null
     * reason) means "can't tell" — we fall through to the sleepers guard.
     */
    private static boolean isKnownNotNightSkip(TimeSkipEvent event) {
        if (GET_SKIP_REASON == null) {
            return false;
        }
        try {
            Object reason = GET_SKIP_REASON.invoke(event);
            return reason != null && !"NIGHT_SKIP".equals(String.valueOf(reason));
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent event) {
        if (isKnownNotNightSkip(event)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("tweaks.sleep-broadcast.enabled", false)) {
            return;
        }

        List<Player> sleepers = new ArrayList<>();
        for (Player player : event.getWorld().getPlayers()) {
            if (player.isSleeping()) {
                sleepers.add(player);
            }
        }
        if (sleepers.isEmpty()) {
            // Shouldn't normally happen for a NIGHT_SKIP, but guard anyway so we
            // never broadcast an empty list. When the skip reason couldn't be
            // resolved, this is also what filters out non-sleep skips such as
            // /time set or another plugin skipping time.
            return;
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            List<Component> visibleNames = new ArrayList<>();
            for (Player sleeper : PlayerVisibility.visibleTo(viewer, sleepers)) {
                Component nickname = NicknameComponentResolver.forPlayer(
                        plugin.getEssentials(), sleeper);
                visibleNames.add(nickname != null
                        ? nickname : Component.text(sleeper.getName()));
            }
            if (visibleNames.isEmpty()) {
                continue;
            }

            String format = visibleNames.size() == 1
                    ? plugin.getConfig().getString(
                            "tweaks.sleep-broadcast.single-format", DEFAULT_SINGLE_FORMAT)
                    : plugin.getConfig().getString(
                            "tweaks.sleep-broadcast.multiple-format", DEFAULT_MULTIPLE_FORMAT);
            viewer.sendMessage(formatMessage(format, visibleNames));
        }
    }

    static Component formatMessage(String format, List<Component> sleepers) {
        if (sleepers.size() == 1) {
            return MINI_MESSAGE.deserialize(format,
                    Placeholder.component("player", sleepers.get(0)));
        }
        return MINI_MESSAGE.deserialize(format,
                Placeholder.component("players", joinNames(sleepers)),
                Placeholder.unparsed("count", String.valueOf(sleepers.size())));
    }

    /**
     * Joins names into a natural, human-readable list: {@code "A and B"} for
     * two, {@code "A, B, and C"} for three or more.
     */
    static Component joinNames(List<Component> names) {
        int size = names.size();
        if (size == 2) {
            return Component.empty().append(names.get(0))
                    .append(Component.text(" and ")).append(names.get(1));
        }
        Component result = Component.empty();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                result = result.append(Component.text(", "));
            }
            if (i == size - 1) {
                result = result.append(Component.text("and "));
            }
            result = result.append(names.get(i));
        }
        return result;
    }
}
