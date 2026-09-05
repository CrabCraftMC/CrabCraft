package crabcraft.net.crabUtilities.bingo;

import org.bukkit.Material;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryAction;

/** Pure regression checks for Bingo #6 mob attribution and post-state policies. */
public final class BingoCardSixMobListenerRegressionTest {
    private BingoCardSixMobListenerRegressionTest() {}

    public static void main(String[] args) {
        acceptsOnlyStoredMendingBooks();
        requiresLethalPositiveLavaDamage();
        acceptsOnlyCarpetActionsThatCanReachTheLlamaDecorSlot();
        verifiesTheRavagerActuallyBecameStunned();
        resolvesPaperSplashPotionSources();
        acceptsOnlySuccessfulPoisonApplications();
        isolatesPersistentMarkersByCardResetAndAge();
        defersPersistentCompletionsAcrossTheStartupCardGap();
        invalidatesDelayedChecksAcrossResets();
    }

    private static void acceptsOnlyStoredMendingBooks() {
        check(BingoCardSixMobListener.isMendingBook(Material.ENCHANTED_BOOK, true),
                "An Enchanted Book with stored Mending must be accepted");
        check(!BingoCardSixMobListener.isMendingBook(Material.ENCHANTED_BOOK, false),
                "An ordinary Enchanted Book must not count");
        check(!BingoCardSixMobListener.isMendingBook(Material.DIAMOND_PICKAXE, true),
                "A Mending tool is not the requested Mending Book");
    }

    private static void requiresLethalPositiveLavaDamage() {
        check(BingoCardSixMobListener.itemDamageIsLethal(5, 5.0, 5.0),
                "Damage equal to item health must be lethal");
        check(BingoCardSixMobListener.itemDamageIsLethal(5, 4.5, 4.5),
                "Paper's integer item-health cast makes a 0.5 remainder lethal");
        check(!BingoCardSixMobListener.itemDamageIsLethal(5, 4.0, 4.0),
                "A book surviving with one health must not complete");
        check(BingoCardSixMobListener.itemDamageIsLethal(4, 4.0, 1.0),
                "Paper kills from original NMS damage even when a plugin lowers event damage");
        check(!BingoCardSixMobListener.itemDamageIsLethal(5, 5.0, 0.0),
                "An event reduced to zero damage is aborted by Paper");
        check(!BingoCardSixMobListener.itemDamageIsLethal(5, Double.NaN, 5.0),
                "Non-finite damage must be rejected");
    }

    private static void acceptsOnlyCarpetActionsThatCanReachTheLlamaDecorSlot() {
        check(BingoCardSixMobListener.clickCanDecorateLlama(
                        1, 2, InventoryAction.PLACE_ALL, true, false, false, false),
                "A carpet cursor placed into the decor slot must arm verification");
        check(BingoCardSixMobListener.clickCanDecorateLlama(
                        1, 2, InventoryAction.HOTBAR_SWAP, false, false, true, false),
                "An offhand carpet swap into the decor slot must be supported");
        check(BingoCardSixMobListener.clickCanDecorateLlama(
                        8,
                        2,
                        InventoryAction.MOVE_TO_OTHER_INVENTORY,
                        false,
                        true,
                        false,
                        false),
                "Shift-clicking a carpet from the player inventory can feed the decor slot");
        check(!BingoCardSixMobListener.clickCanDecorateLlama(
                        8, 2, InventoryAction.PICKUP_ALL, false, true, false, false),
                "Merely picking up a carpet below the Llama inventory must not claim credit");
        check(!BingoCardSixMobListener.clickCanDecorateLlama(
                        0, 2, InventoryAction.PLACE_ALL, true, false, false, false),
                "Trying a carpet in the saddle slot must not arm decor verification");
    }

    private static void verifiesTheRavagerActuallyBecameStunned() {
        check(BingoCardSixMobListener.canStartRavagerStun(true, true, 0),
                "A raised Shield against an unstunned Ravager must arm verification");
        check(!BingoCardSixMobListener.canStartRavagerStun(false, true, 0),
                "Merely holding a Shield must not arm the task");
        check(!BingoCardSixMobListener.canStartRavagerStun(true, false, 0),
                "A custom blocking item plus an idle Shield must not arm the task");
        check(!BingoCardSixMobListener.canStartRavagerStun(true, true, 20),
                "An already-stunned Ravager must not be attributed again");
        check(BingoCardSixMobListener.becameStunned(0, 39),
                "A positive post-attack stun timer proves success");
        check(!BingoCardSixMobListener.becameStunned(0, 0),
                "A failed random stun attempt must not complete");
    }

    private static void resolvesPaperSplashPotionSources() {
        check(BingoCardSixMobListener.splashSourceCanResolvePlayer(true, false),
                "Paper reports the owning Player directly as a splash-potion effect source");
        check(BingoCardSixMobListener.splashSourceCanResolvePlayer(false, true),
                "A projectile source with a Player shooter must remain supported");
        check(!BingoCardSixMobListener.splashSourceCanResolvePlayer(false, false),
                "An unattributed splash source must not receive player credit");
    }

    private static void acceptsOnlySuccessfulPoisonApplications() {
        check(BingoCardSixMobListener.poisonApplicationAccepted(
                        EntityPotionEffectEvent.Action.ADDED, false),
                "A newly added Poison effect must count");
        check(BingoCardSixMobListener.poisonApplicationAccepted(
                        EntityPotionEffectEvent.Action.CHANGED, true),
                "A Poison effect that successfully overrides the old effect must count");
        check(!BingoCardSixMobListener.poisonApplicationAccepted(
                        EntityPotionEffectEvent.Action.CHANGED, false),
                "A rejected weaker Poison replacement must not count");
        check(!BingoCardSixMobListener.poisonApplicationAccepted(
                        EntityPotionEffectEvent.Action.REMOVED, true),
                "Removing Poison must not satisfy the task");
    }

    private static void isolatesPersistentMarkersByCardResetAndAge() {
        long now = 20_000L;
        check(BingoCardSixMobListener.markerIsCurrent(
                        6, 6, 19_000L, now, 7L, 7L, 2_000L),
                "A recent same-card marker after reset must remain current");
        check(!BingoCardSixMobListener.markerIsCurrent(
                        5, 6, 19_000L, now, 7L, 7L, 2_000L),
                "A marker from Card #5 must not transfer to Card #6");
        check(!BingoCardSixMobListener.markerIsCurrent(
                        6, Integer.MIN_VALUE, 19_000L, now, 7L, 7L, 2_000L),
                "No marker is usable without an active card");
        check(!BingoCardSixMobListener.markerIsCurrent(
                        6, 6, 19_000L, now, 6L, 7L, 5_000L),
                "A marker from a previous persisted player run must be rejected");
        check(!BingoCardSixMobListener.markerIsCurrent(
                        6, 6, 17_999L, now, 7L, 7L, 2_000L),
                "An expired marker must be rejected");
        check(!BingoCardSixMobListener.markerIsCurrent(
                        6, 6, 20_001L, now, 7L, 7L, 2_000L),
                "A future marker timestamp must be rejected");
    }

    private static void defersPersistentCompletionsAcrossTheStartupCardGap() {
        check(BingoCardSixMobListener.shouldDeferPersistentCompletion(
                        6, Integer.MIN_VALUE, true, true, 0, 300),
                "A terminal persisted action must wait while Redis restores the active card");
        check(BingoCardSixMobListener.shouldDeferPersistentCompletion(
                        6, 6, false, true, 0, 300),
                "A terminal persisted action must wait briefly for its owner to reconnect");
        check(!BingoCardSixMobListener.shouldDeferPersistentCompletion(
                        6, Integer.MIN_VALUE, true, false, 0, 300),
                "An expired marker must not be deferred");
        check(!BingoCardSixMobListener.shouldDeferPersistentCompletion(
                        6, Integer.MIN_VALUE, true, true, 300, 300),
                "Startup deferral must be bounded");
        check(!BingoCardSixMobListener.shouldDeferPersistentCompletion(
                        5, 6, false, true, 0, 300),
                "A known mismatching card must not retry for an offline owner");
    }

    private static void invalidatesDelayedChecksAcrossResets() {
        check(BingoCardSixMobListener.attemptIsCurrent(3, 4, 3, 4),
                "An unchanged detector/player generation must remain current");
        check(!BingoCardSixMobListener.attemptIsCurrent(4, 4, 3, 4),
                "A detector clear must invalidate pending work");
        check(!BingoCardSixMobListener.attemptIsCurrent(3, 5, 3, 4),
                "A player reset must invalidate pending work");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
