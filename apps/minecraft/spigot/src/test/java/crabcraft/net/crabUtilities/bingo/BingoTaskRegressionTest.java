package crabcraft.net.crabUtilities.bingo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Dependency-free regression checks for the deployed harder Bingo #1 catalogue. */
public final class BingoTaskRegressionTest {
    private BingoTaskRegressionTest() {}

    public static void main(String[] args) {
        List<BingoTask> expected = List.of(
                BingoTask.GROW_TREE_IN_NETHER,
                BingoTask.PLAY_FIVE_GOAT_HORNS,
                BingoTask.CONNECT_ALL_ORE_TYPES,
                BingoTask.ACTIVATE_TOTEM,
                BingoTask.BREED_MULE,
                BingoTask.KILL_HOSTILE_WITH_ANVIL,
                BingoTask.TWO_CREEPERS_ONE_BOAT,
                BingoTask.IGNITE_CAMPFIRE_FROM_DISTANCE,
                BingoTask.BREED_SNIFFERS_COLLECT_EGG,
                BingoTask.CURE_ZOMBIE_VILLAGER,
                BingoTask.GAIN_AXOLOTL_REGENERATION,
                BingoTask.EQUIP_PIGLIN_BRUTE_AXE,
                BingoTask.KILL_HOSTILE_FROM_CAMEL,
                BingoTask.DUPLICATE_ALLAY,
                BingoTask.COLLAPSE_SCAFFOLDING_TOWER,
                BingoTask.BREED_TRUSTING_FOX);
        check(BingoTask.ordered().equals(expected), "Harder Bingo #1 order changed");

        Set<String> ids = new HashSet<>();
        for (BingoTask task : BingoTask.ordered()) {
            check(ids.add(task.id()), "Duplicate task ID: " + task.id());
            check(BingoTask.fromId(task.id()).orElseThrow() == task, "Task is not resolvable");
        }
        check(BingoTask.fromId("leash_rabbit").isEmpty(), "Retired card task is still advertised");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
