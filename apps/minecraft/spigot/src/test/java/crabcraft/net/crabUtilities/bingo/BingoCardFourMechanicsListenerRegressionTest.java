package crabcraft.net.crabUtilities.bingo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.FaceAttachable;

/** Regression checks for Bingo #4's bounded mechanics correlations. */
public final class BingoCardFourMechanicsListenerRegressionTest {
    private BingoCardFourMechanicsListenerRegressionTest() {}

    public static void main(String[] args) {
        check(
                BingoCardFourMechanicsListener.isBeeNest(Material.BEE_NEST),
                "A generated Bee Nest must satisfy the tree task");
        check(
                !BingoCardFourMechanicsListener.isBeeNest(Material.BEEHIVE),
                "A crafted Beehive must not satisfy the tree task");

        check(
                BingoCardFourMechanicsListener.leverSupportFace(
                                FaceAttachable.AttachedFace.FLOOR, BlockFace.NORTH)
                        == BlockFace.DOWN,
                "A floor Lever must be attached to the block below it");
        check(
                BingoCardFourMechanicsListener.leverSupportFace(
                                FaceAttachable.AttachedFace.CEILING, BlockFace.NORTH)
                        == BlockFace.UP,
                "A ceiling Lever must be attached to the block above it");
        check(
                BingoCardFourMechanicsListener.leverSupportFace(
                                FaceAttachable.AttachedFace.WALL, BlockFace.EAST)
                        == BlockFace.WEST,
                "A wall Lever's support must be opposite its facing direction");
        check(
                BingoCardFourMechanicsListener.isWithinWindow(100, 100, 1),
                "A same-tick mechanism event must correlate");
        check(
                BingoCardFourMechanicsListener.isWithinWindow(100, 101, 1),
                "A next-tick mechanism event must correlate");
        check(
                !BingoCardFourMechanicsListener.isWithinWindow(100, 102, 1),
                "A later mechanism event must not reuse stale attribution");
        check(
                BingoCardFourMechanicsListener.isPistonPushCorrelation(100, 101, 12),
                "A Lever-triggered maximum Piston load must satisfy the task");
        check(
                !BingoCardFourMechanicsListener.isPistonPushCorrelation(100, 101, 11),
                "A Piston pushing fewer than 12 blocks must not satisfy the task");
        List<PistonMoveReaction> twelvePushedAndOneBroken = new ArrayList<>(
                Collections.nCopies(12, PistonMoveReaction.MOVE));
        twelvePushedAndOneBroken.add(PistonMoveReaction.BREAK);
        check(
                BingoCardFourMechanicsListener.countPushedReactions(
                                twelvePushedAndOneBroken)
                        == 12,
                "A breakable front block must not inflate the Piston's pushed-block count");

        UUID world = UUID.randomUUID();
        check(
                BingoCardFourMechanicsListener.isNearby(
                        world, 10.0, 64.5, 10.0,
                        world, 11.0, 64.5, 10.0,
                        4.0),
                "A nearby same-world Sculk bloom must correlate");
        check(
                !BingoCardFourMechanicsListener.isNearby(
                        world, 10.0, 64.5, 10.0,
                        UUID.randomUUID(), 10.0, 64.5, 10.0,
                        4.0),
                "A Sculk bloom in another world must not correlate");

        UUID owner = UUID.randomUUID();
        check(
                owner.equals(BingoCardFourMechanicsListener.singleDistinctOwner(
                        List.of(owner, owner))),
                "Duplicate evidence for one player must remain attributable");
        check(
                BingoCardFourMechanicsListener.singleDistinctOwner(
                                List.of(owner, UUID.randomUUID()))
                        == null,
                "Overlapping evidence from different players must be rejected");

        check(
                BingoCardFourMechanicsListener.canStartHoneyCure(
                        Material.HONEY_BOTTLE, true),
                "A Honey Bottle consumed while poisoned must start confirmation");
        check(
                !BingoCardFourMechanicsListener.canStartHoneyCure(
                        Material.HONEY_BOTTLE, false),
                "A Honey Bottle consumed without Poison must not count");
        check(
                !BingoCardFourMechanicsListener.canStartHoneyCure(
                        Material.MILK_BUCKET, true),
                "A different cure must not satisfy the Honey Bottle task");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
