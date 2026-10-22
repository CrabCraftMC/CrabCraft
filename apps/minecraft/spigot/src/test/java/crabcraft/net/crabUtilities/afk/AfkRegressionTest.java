package crabcraft.net.crabUtilities.afk;

import java.time.Duration;
import java.util.UUID;

final class AfkRegressionTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final long FIVE_MINUTES = Duration.ofMinutes(5).toNanos();

    public static void main(String[] args) {
        AfkTracker tracker = new AfkTracker(FIVE_MINUTES);
        tracker.add(PLAYER, 0L);
        check(!tracker.markIdle(PLAYER, FIVE_MINUTES - 1L), "AFK began before five idle minutes");
        check(tracker.markIdle(PLAYER, FIVE_MINUTES), "AFK did not begin after five idle minutes");
        check(tracker.isAfk(PLAYER), "the placeholder state did not reflect automatic AFK");
        check(!tracker.markIdle(PLAYER, FIVE_MINUTES + 1L), "idle polling repeated the announcement");
        check(tracker.recordActivity(PLAYER, FIVE_MINUTES + 2L), "activity did not clear automatic AFK");
        check(!tracker.isAfk(PLAYER), "AFK stayed set after activity");
        check(!tracker.recordActivity(PLAYER, FIVE_MINUTES + 3L), "ordinary activity announced a return");
        check(!tracker.markIdle(PLAYER, 2L * FIVE_MINUTES), "activity did not restart the idle timeout");

        check(tracker.toggle(PLAYER, 2L * FIVE_MINUTES), "manual /afk did not enable AFK");
        check(!tracker.toggle(PLAYER, 2L * FIVE_MINUTES + 1L), "/afk did not toggle AFK off");
        check(!tracker.markIdle(PLAYER, 3L * FIVE_MINUTES), "leaving manual AFK immediately marked AFK again");
        check(tracker.toggle(PLAYER, 3L * FIVE_MINUTES), "manual AFK could not be re-enabled");
        check(tracker.recordActivity(PLAYER, 3L * FIVE_MINUTES + 1L), "movement did not clear manual AFK");

        tracker.toggle(PLAYER, 4L * FIVE_MINUTES);
        tracker.remove(PLAYER);
        check(!tracker.isAfk(PLAYER), "disconnected players stayed AFK");
        check(!tracker.recordActivity(PLAYER, 4L * FIVE_MINUTES), "delayed chat resurrected disconnected state");
        tracker.add(PLAYER, 4L * FIVE_MINUTES);
        check(!tracker.isAfk(PLAYER), "rejoining restored old AFK state");
        tracker.clear();
        check(!tracker.isAfk(PLAYER), "shutdown retained AFK state");

        check(AfkManager.isAfkCommand("/afk"), "AFK preprocessing broke the command toggle");
        check(AfkManager.isAfkCommand("/CrabUtilities:AFK"), "namespaced AFK did not preserve its toggle");
        check(!AfkManager.isAfkCommand("/msg Afk hello"), "an ordinary command was treated as /afk");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
