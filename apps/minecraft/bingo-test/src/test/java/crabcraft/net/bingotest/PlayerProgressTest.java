package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small dependency-free regression test; run this class with assertions enabled. */
public final class PlayerProgressTest {
    private static final List<String> EXPECTED_IDS = List.of(
            "sulfur_cube_diamond_bucket",
            "snow_golem_kills_blaze",
            "four_by_four_nether_portal",
            "crossbow_firework_kill_two",
            "happy_ghast_hostile_boat",
            "spear_hit_three",
            "silverfish_hide_in_stone",
            "tree_with_bee_nest",
            "brown_mooshroom_wither_stew",
            "piston_push_twelve",
            "ender_pearl_teleport_hundred",
            "reflected_breeze_wind_charge",
            "projectile_smash_filled_pot",
            "cure_poison_honey_bottle",
            "freeze_skeleton_stray",
            "sculk_catalyst_player_kill");

    private PlayerProgressTest() {}

    public static void main(String[] args) {
        cardFourIdsAreUniqueAndInOrder();
        constructorRequiresExactlySixteenUniqueTasks();
        constructorDefensivelyCopiesAllowedTasks();
        directCompletionIsIdempotent();
        earlierCardCompletionIsRejected();
        checklistCompletesOnlyAfterAllCardFourTasks();
        completedTasksViewIsImmutable();
        resetClearsAllProgress();
    }

    private static void cardFourIdsAreUniqueAndInOrder() {
        List<BingoTask> tasks = BingoTask.cardFour();
        check(tasks.stream().map(BingoTask::id).toList().equals(EXPECTED_IDS),
                "Bingo #4 task order changed");
        check(new HashSet<>(tasks).size() == 16, "Bingo #4 tasks must be unique");

        Set<String> ids = new HashSet<>();
        for (BingoTask task : tasks) {
            check(ids.add(task.id()), "Duplicate task ID " + task.id());
            check(BingoTask.fromId(task.id()).orElseThrow() == task, "Task ID lookup failed");
        }
    }

    private static void constructorRequiresExactlySixteenUniqueTasks() {
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.cardFour().subList(0, 15)),
                "A 15-task test card was accepted");
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.allDeployed()),
                "The deployed catalogue was accepted as one test card");

        List<BingoTask> duplicated = new ArrayList<>(BingoTask.cardFour());
        duplicated.set(duplicated.size() - 1, duplicated.get(0));
        expectIllegalArgument(
                () -> new PlayerProgress(duplicated),
                "A Card #4 task list containing a duplicate was accepted");
    }

    private static void constructorDefensivelyCopiesAllowedTasks() {
        List<BingoTask> mutableTasks = new ArrayList<>(BingoTask.cardFour());
        PlayerProgress progress = new PlayerProgress(mutableTasks);
        BingoTask first = mutableTasks.get(0);
        mutableTasks.clear();

        check(progress.complete(first), "Mutating the constructor input changed allowed tasks");
        check(progress.completedCount() == 1, "Defensive-copy test recorded the wrong total");
    }

    private static void directCompletionIsIdempotent() {
        BingoTask task = BingoTask.cardFour().get(0);
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFour());
        check(progress.complete(task), "First completion should be new");
        check(!progress.complete(task), "Repeated completion should be ignored");
        check(progress.completedCount() == 1, "Repeated completion changed the total");
    }

    private static void earlierCardCompletionIsRejected() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFour());
        for (BingoTask earlierTask : List.of(
                BingoTask.cardOne().get(0),
                BingoTask.cardTwo().get(0),
                BingoTask.cardThree().get(0))) {
            check(!progress.complete(earlierTask), "An earlier-card task was accepted");
            check(!progress.isComplete(earlierTask), "An earlier-card task appears complete");
        }
        check(progress.completedCount() == 0, "An earlier-card task changed the total");
    }

    private static void checklistCompletesOnlyAfterAllCardFourTasks() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFour());
        List<BingoTask> tasks = BingoTask.cardFour();
        check(!progress.isChecklistComplete(), "A fresh checklist started complete");
        for (int index = 0; index < tasks.size() - 1; index++) {
            check(progress.complete(tasks.get(index)), "Card #4 task was rejected");
        }
        check(!progress.isChecklistComplete(), "Checklist completed with one task missing");
        check(progress.complete(tasks.get(tasks.size() - 1)), "Final task was rejected");
        check(progress.isChecklistComplete(), "All Card #4 tasks did not complete checklist");
    }

    private static void completedTasksViewIsImmutable() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFour());
        progress.complete(BingoTask.cardFour().get(0));
        Set<BingoTask> completed = progress.completedTasks();
        try {
            completed.add(BingoTask.cardFour().get(1));
            throw new AssertionError("Completed-task view is mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: callers cannot mutate progress without complete/reset.
        }
        check(progress.completedCount() == 1, "Completed-task view leaked mutable state");
    }

    private static void resetClearsAllProgress() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFour());
        for (BingoTask task : BingoTask.cardFour()) {
            progress.complete(task);
        }
        progress.reset();

        check(progress.completedCount() == 0, "Reset did not clear completed tasks");
        check(progress.completedTasks().isEmpty(), "Reset left completed tasks behind");
        check(!progress.isChecklistComplete(), "Reset checklist remained complete");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
