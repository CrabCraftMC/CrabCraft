package crabcraft.net.crabUtilities.bingo;

import org.bukkit.entity.EntityType;

/** Pure regression checks for Bingo #5 challenge attribution and completion policies. */
public final class BingoCardFiveChallengeListenerRegressionTest {
    private BingoCardFiveChallengeListenerRegressionTest() {}

    public static void main(String[] args) {
        endermanMarkerSurvivesOnlyPermittedDamage();
        checksCrossbowBreakBoundariesWithoutOverflow();
        requiresAnActualThrownChickenHatch();
        keepsPersistentAttributionOnlyForTheSameCard();
        expiresPersistentAttributionAcrossTimeAndPlayerResetBoundaries();
    }

    private static void endermanMarkerSurvivesOnlyPermittedDamage() {
        check(
                BingoCardFiveChallengeListener.ChallengePolicy.retainsEndermanMarker(0.0, false),
                "Zero final damage must not invalidate an otherwise valid Enderman attempt");
        check(
                BingoCardFiveChallengeListener.ChallengePolicy.retainsEndermanMarker(4.0, true),
                "Positive Endermite melee damage must retain the Enderman marker");
        check(
                !BingoCardFiveChallengeListener.ChallengePolicy.retainsEndermanMarker(
                        0.5, false),
                "Any positive non-Endermite damage must invalidate the Enderman marker");
    }

    private static void checksCrossbowBreakBoundariesWithoutOverflow() {
        check(
                BingoCardFiveChallengeListener.ChallengePolicy.damageBreaksItem(99, 100, 1),
                "Damage that reaches maximum durability must break the Crossbow");
        check(
                !BingoCardFiveChallengeListener.ChallengePolicy.damageBreaksItem(98, 100, 1),
                "Damage below maximum durability must not count as a broken Crossbow");
        check(
                !BingoCardFiveChallengeListener.ChallengePolicy.damageBreaksItem(-1, 100, 1),
                "Invalid negative current damage must be rejected");
        check(
                !BingoCardFiveChallengeListener.ChallengePolicy.damageBreaksItem(99, 0, 1),
                "A non-damageable item must be rejected");
        check(
                !BingoCardFiveChallengeListener.ChallengePolicy.damageBreaksItem(99, 100, 0),
                "A zero-damage event must not look like an item break");
        check(
                BingoCardFiveChallengeListener.ChallengePolicy.damageBreaksItem(
                        Integer.MAX_VALUE - 5, Integer.MAX_VALUE, 10),
                "Durability addition must not overflow before the break comparison");
    }

    private static void requiresAnActualThrownChickenHatch() {
        check(
                BingoCardFiveChallengeListener.ChallengePolicy.isChickenHatch(
                        true, 1, EntityType.CHICKEN),
                "A thrown Egg that hatches at least one Chicken must count");
        check(
                !BingoCardFiveChallengeListener.ChallengePolicy.isChickenHatch(
                        false, 1, EntityType.CHICKEN),
                "An Egg event that did not hatch must not count");
        check(
                !BingoCardFiveChallengeListener.ChallengePolicy.isChickenHatch(
                        true, 0, EntityType.CHICKEN),
                "A zero-entity hatch must not count");
        check(
                !BingoCardFiveChallengeListener.ChallengePolicy.isChickenHatch(
                        true, 1, EntityType.COW),
                "A non-Chicken hatch type must not satisfy the task");
    }

    private static void keepsPersistentAttributionOnlyForTheSameCard() {
        check(
                BingoCardFiveChallengeListener.markerMatchesCard(5, 5),
                "Attribution for the same active card must survive a listener reload");
        check(
                !BingoCardFiveChallengeListener.markerMatchesCard(5, 6),
                "Attribution from a different card must be rejected");
        check(
                !BingoCardFiveChallengeListener.markerMatchesCard(
                        5, Integer.MIN_VALUE),
                "Attribution must be rejected while no card is active");
    }

    private static void expiresPersistentAttributionAcrossTimeAndPlayerResetBoundaries() {
        long now = 10_000L;
        check(
                BingoCardFiveChallengeListener.isFreshAttribution(
                        9_000L, now, 8_500L, 2_000L),
                "Recent attribution created after a player reset must remain valid");
        check(
                !BingoCardFiveChallengeListener.isFreshAttribution(
                        8_500L, now, 8_500L, 5_000L),
                "Attribution from at or before a player reset must be invalid");
        check(
                !BingoCardFiveChallengeListener.isFreshAttribution(
                        7_999L, now, 0L, 2_000L),
                "Expired Enderman or End Crystal attribution must be invalid");
        check(
                !BingoCardFiveChallengeListener.isFreshAttribution(
                        10_001L, now, 0L, 2_000L),
                "Future attribution timestamps must be invalid");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
