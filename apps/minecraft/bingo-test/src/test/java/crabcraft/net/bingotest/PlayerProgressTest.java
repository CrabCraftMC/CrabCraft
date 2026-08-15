package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small dependency-free regression test; run this class with assertions enabled. */
public final class PlayerProgressTest {
    private static final List<String> EXPECTED_IDS = List.of(
            "sulfur_cube_tnt_ignite",
            "breed_third_colour_sheep",
            "unlock_ominous_vault",
            "fox_uses_totem",
            "tame_nautilus",
            "johnny_vindicator_kill",
            "hook_ghast",
            "water_bottle_extinguish_three",
            "shulker_bullet_duplicate",
            "warm_ridden_strider",
            "raid_bell_reveal_three",
            "piercing_arrow_hit_three",
            "leashed_frog_froglight",
            "charged_creeper_mob_head",
            "golden_dandelion_hoglin",
            "four_copper_trumpet_sounds");

    private PlayerProgressTest() {}

    public static void main(String[] args) {
        cardThreeIdsAreUniqueAndInOrder();
        constructorRequiresExactlySixteenUniqueTasks();
        constructorDefensivelyCopiesAllowedTasks();
        directCompletionIsIdempotent();
        earlierCardCompletionIsRejected();
        checklistCompletesOnlyAfterAllCardThreeTasks();
        completedTasksViewIsImmutable();
        resetClearsAllProgress();
    }

    private static void cardThreeIdsAreUniqueAndInOrder() {
        List<BingoTask> tasks = BingoTask.cardThree();
        check(tasks.stream().map(BingoTask::id).toList().equals(EXPECTED_IDS),
                "Bingo #3 task order changed");
        check(new HashSet<>(tasks).size() == 16, "Bingo #3 tasks must be unique");

        Set<String> ids = new HashSet<>();
        for (BingoTask task : tasks) {
            check(ids.add(task.id()), "Duplicate task ID " + task.id());
            check(BingoTask.fromId(task.id()).orElseThrow() == task, "Task ID lookup failed");
        }
    }

    private static void constructorRequiresExactlySixteenUniqueTasks() {
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.cardThree().subList(0, 15)),
                "A 15-task test card was accepted");
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.allDeployed()),
                "The deployed catalogue was accepted as one test card");

        List<BingoTask> duplicated = new ArrayList<>(BingoTask.cardThree());
        duplicated.set(duplicated.size() - 1, duplicated.get(0));
        expectIllegalArgument(
                () -> new PlayerProgress(duplicated),
                "A Card #3 task list containing a duplicate was accepted");
    }

    private static void constructorDefensivelyCopiesAllowedTasks() {
        List<BingoTask> mutableTasks = new ArrayList<>(BingoTask.cardThree());
        PlayerProgress progress = new PlayerProgress(mutableTasks);
        BingoTask first = mutableTasks.get(0);
        mutableTasks.clear();

        check(progress.complete(first), "Mutating the constructor input changed allowed tasks");
        check(progress.completedCount() == 1, "Defensive-copy test recorded the wrong total");
    }

    private static void directCompletionIsIdempotent() {
        BingoTask task = BingoTask.cardThree().get(0);
        PlayerProgress progress = new PlayerProgress(BingoTask.cardThree());
        check(progress.complete(task), "First completion should be new");
        check(!progress.complete(task), "Repeated completion should be ignored");
        check(progress.completedCount() == 1, "Repeated completion changed the total");
    }

    private static void earlierCardCompletionIsRejected() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardThree());
        for (BingoTask earlierTask : List.of(
                BingoTask.cardOne().get(0), BingoTask.cardTwo().get(0))) {
            check(!progress.complete(earlierTask), "An earlier-card task was accepted");
            check(!progress.isComplete(earlierTask), "An earlier-card task appears complete");
        }
        check(progress.completedCount() == 0, "An earlier-card task changed the total");
    }

    private static void checklistCompletesOnlyAfterAllCardThreeTasks() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardThree());
        List<BingoTask> tasks = BingoTask.cardThree();
        check(!progress.isChecklistComplete(), "A fresh checklist started complete");
        for (int index = 0; index < tasks.size() - 1; index++) {
            check(progress.complete(tasks.get(index)), "Card #3 task was rejected");
        }
        check(!progress.isChecklistComplete(), "Checklist completed with one task missing");
        check(progress.complete(tasks.get(tasks.size() - 1)), "Final task was rejected");
        check(progress.isChecklistComplete(), "All Card #3 tasks did not complete checklist");
    }

    private static void completedTasksViewIsImmutable() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardThree());
        progress.complete(BingoTask.cardThree().get(0));
        Set<BingoTask> completed = progress.completedTasks();
        try {
            completed.add(BingoTask.cardThree().get(1));
            throw new AssertionError("Completed-task view is mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: callers cannot mutate progress without complete/reset.
        }
        check(progress.completedCount() == 1, "Completed-task view leaked mutable state");
    }

    private static void resetClearsAllProgress() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardThree());
        for (BingoTask task : BingoTask.cardThree()) {
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
