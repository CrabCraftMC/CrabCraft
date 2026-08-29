package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small dependency-free regression test; run this class with assertions enabled. */
public final class PlayerProgressTest {
    private static final List<String> EXPECTED_IDS = List.of(
            "build_ten_tall_dripleaf",
            "mob_equips_dropped_helmet",
            "clean_banner_pattern",
            "feed_panda_cake",
            "remove_enchantment_grindstone",
            "name_hoglin_zoglin",
            "lodestone_compass",
            "enderman_killed_by_endermites_only",
            "fill_chiseled_bookshelf_enchanted",
            "disarm_pillager",
            "hatch_thrown_chicken",
            "snow_every_height",
            "repair_iron_golem",
            "power_furnace_minecart",
            "player_end_crystal_hostile_kill",
            "wear_four_armour_materials");

    private PlayerProgressTest() {}

    public static void main(String[] args) {
        cardFiveIdsAreUniqueAndInOrder();
        constructorRequiresExactlySixteenUniqueTasks();
        constructorDefensivelyCopiesAllowedTasks();
        directCompletionIsIdempotent();
        earlierCardCompletionIsRejected();
        checklistCompletesOnlyAfterAllCardFiveTasks();
        completedTasksViewIsImmutable();
        resetClearsAllProgress();
    }

    private static void cardFiveIdsAreUniqueAndInOrder() {
        List<BingoTask> tasks = BingoTask.cardFive();
        check(tasks.stream().map(BingoTask::id).toList().equals(EXPECTED_IDS),
                "Bingo #5 task order changed");
        check(new HashSet<>(tasks).size() == 16, "Bingo #5 tasks must be unique");

        Set<String> ids = new HashSet<>();
        for (BingoTask task : tasks) {
            check(ids.add(task.id()), "Duplicate task ID " + task.id());
            check(BingoTask.fromId(task.id()).orElseThrow() == task, "Task ID lookup failed");
        }
    }

    private static void constructorRequiresExactlySixteenUniqueTasks() {
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.cardFive().subList(0, 15)),
                "A 15-task test card was accepted");
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.allDeployed()),
                "The deployed catalogue was accepted as one test card");

        List<BingoTask> duplicated = new ArrayList<>(BingoTask.cardFive());
        duplicated.set(duplicated.size() - 1, duplicated.get(0));
        expectIllegalArgument(
                () -> new PlayerProgress(duplicated),
                "A Card #5 task list containing a duplicate was accepted");
    }

    private static void constructorDefensivelyCopiesAllowedTasks() {
        List<BingoTask> mutableTasks = new ArrayList<>(BingoTask.cardFive());
        PlayerProgress progress = new PlayerProgress(mutableTasks);
        BingoTask first = mutableTasks.get(0);
        mutableTasks.clear();

        check(progress.complete(first), "Mutating the constructor input changed allowed tasks");
        check(progress.completedCount() == 1, "Defensive-copy test recorded the wrong total");
    }

    private static void directCompletionIsIdempotent() {
        BingoTask task = BingoTask.cardFive().get(0);
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFive());
        check(progress.complete(task), "First completion should be new");
        check(!progress.complete(task), "Repeated completion should be ignored");
        check(progress.completedCount() == 1, "Repeated completion changed the total");
    }

    private static void earlierCardCompletionIsRejected() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFive());
        for (BingoTask earlierTask : List.of(
                BingoTask.cardOne().get(0),
                BingoTask.cardTwo().get(0),
                BingoTask.cardThree().get(0),
                BingoTask.cardFour().get(0))) {
            check(!progress.complete(earlierTask), "An earlier-card task was accepted");
            check(!progress.isComplete(earlierTask), "An earlier-card task appears complete");
        }
        check(progress.completedCount() == 0, "An earlier-card task changed the total");
    }

    private static void checklistCompletesOnlyAfterAllCardFiveTasks() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFive());
        List<BingoTask> tasks = BingoTask.cardFive();
        check(!progress.isChecklistComplete(), "A fresh checklist started complete");
        for (int index = 0; index < tasks.size() - 1; index++) {
            check(progress.complete(tasks.get(index)), "Card #5 task was rejected");
        }
        check(!progress.isChecklistComplete(), "Checklist completed with one task missing");
        check(progress.complete(tasks.get(tasks.size() - 1)), "Final task was rejected");
        check(progress.isChecklistComplete(), "All Card #5 tasks did not complete checklist");
    }

    private static void completedTasksViewIsImmutable() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFive());
        progress.complete(BingoTask.cardFive().get(0));
        Set<BingoTask> completed = progress.completedTasks();
        try {
            completed.add(BingoTask.cardFive().get(1));
            throw new AssertionError("Completed-task view is mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: callers cannot mutate progress without complete/reset.
        }
        check(progress.completedCount() == 1, "Completed-task view leaked mutable state");
    }

    private static void resetClearsAllProgress() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardFive());
        for (BingoTask task : BingoTask.cardFive()) {
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
