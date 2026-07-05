package crabcraft.net.crabUtilities.sleep;

import crabcraft.net.crabUtilities.CrabUtilities;
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
 * <p>The player name fills a MiniMessage {@link Placeholder#unparsed} so names
 * (which can contain arbitrary text via nicknames elsewhere) are never parsed
 * as MiniMessage tags.
 */
public class SleepBroadcastListener implements Listener {

    // Uses the plugin's pastel palette (see SettingsDialog): gold #FCD05C for
    // the highlighted player name(s), grey #b0b0b0 for the body text.
    private static final String DEFAULT_SINGLE_FORMAT =
            "<#FCD05C><player></#FCD05C><#b0b0b0> slept to skip the night.</#b0b0b0>";
    private static final String DEFAULT_MULTIPLE_FORMAT =
            "<#FCD05C><players></#FCD05C><#b0b0b0> slept to skip the night.</#b0b0b0>";

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
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

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
        if (!plugin.getConfig().getBoolean("sleep-broadcast.enabled", false)) {
            return;
        }

        List<String> sleepers = new ArrayList<>();
        for (Player player : event.getWorld().getPlayers()) {
            if (player.isSleeping()) {
                sleepers.add(player.getName());
            }
        }
        if (sleepers.isEmpty()) {
            // Shouldn't normally happen for a NIGHT_SKIP, but guard anyway so we
            // never broadcast an empty list. When the skip reason couldn't be
            // resolved, this is also what filters out non-sleep skips such as
            // /time set or another plugin skipping time.
            return;
        }

        Component message;
        if (sleepers.size() == 1) {
            String format = plugin.getConfig().getString(
                    "sleep-broadcast.single-format", DEFAULT_SINGLE_FORMAT);
            message = miniMessage.deserialize(format,
                    Placeholder.unparsed("player", sleepers.get(0)));
        } else {
            String format = plugin.getConfig().getString(
                    "sleep-broadcast.multiple-format", DEFAULT_MULTIPLE_FORMAT);
            message = miniMessage.deserialize(format,
                    Placeholder.unparsed("players", joinNames(sleepers)),
                    Placeholder.unparsed("count", String.valueOf(sleepers.size())));
        }

        Bukkit.broadcast(message);
    }

    /**
     * Joins names into a natural, human-readable list: {@code "A and B"} for
     * two, {@code "A, B, and C"} for three or more.
     */
    private static String joinNames(List<String> names) {
        int size = names.size();
        if (size == 2) {
            return names.get(0) + " and " + names.get(1);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            if (i == size - 1) {
                builder.append("and ");
            }
            builder.append(names.get(i));
        }
        return builder.toString();
    }
}
