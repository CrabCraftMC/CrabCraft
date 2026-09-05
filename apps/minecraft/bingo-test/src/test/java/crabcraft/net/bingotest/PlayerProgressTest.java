package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small dependency-free regression test; run this class with assertions enabled. */
public final class PlayerProgressTest {
    private static final List<String> EXPECTED_IDS = List.of(
            "hang_four_by_four_painting",
            "outline_hanging_sign",
            "fill_campfire_four_slots",
            "shoot_button_with_arrow",
            "fish_treasure_and_junk",
            "carpet_llama",
            "enchant_five_items",
            "throw_mending_book_in_lava",
            "stun_ravager",
            "fully_power_conduit",
            "poison_bee",
            "place_fish_in_nether",
            "named_ghast_overworld",
            "fill_ender_chest",
            "apply_armour_trim",
            "four_sherd_decorated_pot");

    private PlayerProgressTest() {}

    public static void main(String[] args) {
        cardSixIdsAreUniqueAndInOrder();
        constructorRequiresExactlySixteenUniqueTasks();
        constructorDefensivelyCopiesAllowedTasks();
        directCompletionIsIdempotent();
        earlierCardCompletionIsRejected();
        checklistCompletesOnlyAfterAllCardSixTasks();
        completedTasksViewIsImmutable();
        resetClearsAllProgress();
    }

    private static void cardSixIdsAreUniqueAndInOrder() {
        List<BingoTask> tasks = BingoTask.cardSix();
        List<String> actualIds = tasks.stream().map(BingoTask::id).toList();
        check(actualIds.size() == EXPECTED_IDS.size(),
                "Bingo #6 task count changed: " + actualIds.size());
        for (int index = 0; index < EXPECTED_IDS.size(); index++) {
            check(actualIds.get(index).equals(EXPECTED_IDS.get(index)),
                    "Bingo #6 task " + (index + 1) + " changed: actual="
                            + actualIds.get(index) + ", expected=" + EXPECTED_IDS.get(index));
        }
        check(new HashSet<>(tasks).size() == 16, "Bingo #6 tasks must be unique");

        Set<String> ids = new HashSet<>();
        for (BingoTask task : tasks) {
            check(ids.add(task.id()), "Duplicate task ID " + task.id());
            check(BingoTask.fromId(task.id()).orElseThrow() == task, "Task ID lookup failed");
        }
    }

    private static void constructorRequiresExactlySixteenUniqueTasks() {
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.cardSix().subList(0, 15)),
                "A 15-task test card was accepted");
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.allDeployed()),
                "The deployed catalogue was accepted as one test card");

        List<BingoTask> duplicated = new ArrayList<>(BingoTask.cardSix());
        duplicated.set(duplicated.size() - 1, duplicated.get(0));
        expectIllegalArgument(
                () -> new PlayerProgress(duplicated),
                "A Card #6 task list containing a duplicate was accepted");
    }

    private static void constructorDefensivelyCopiesAllowedTasks() {
        List<BingoTask> mutableTasks = new ArrayList<>(BingoTask.cardSix());
        PlayerProgress progress = new PlayerProgress(mutableTasks);
        BingoTask first = mutableTasks.get(0);
        mutableTasks.clear();

        check(progress.complete(first), "Mutating the constructor input changed allowed tasks");
        check(progress.completedCount() == 1, "Defensive-copy test recorded the wrong total");
    }

    private static void directCompletionIsIdempotent() {
        BingoTask task = BingoTask.cardSix().get(0);
        PlayerProgress progress = new PlayerProgress(BingoTask.cardSix());
        check(progress.complete(task), "First completion should be new");
        check(!progress.complete(task), "Repeated completion should be ignored");
        check(progress.completedCount() == 1, "Repeated completion changed the total");
    }

    private static void earlierCardCompletionIsRejected() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardSix());
        for (BingoTask earlierTask : List.of(
                BingoTask.cardOne().get(0),
                BingoTask.cardTwo().get(0),
                BingoTask.cardThree().get(0),
                BingoTask.cardFour().get(0),
                BingoTask.cardFive().get(0))) {
            check(!progress.complete(earlierTask), "An earlier-card task was accepted");
            check(!progress.isComplete(earlierTask), "An earlier-card task appears complete");
        }
        check(progress.completedCount() == 0, "An earlier-card task changed the total");
    }

    private static void checklistCompletesOnlyAfterAllCardSixTasks() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardSix());
        List<BingoTask> tasks = BingoTask.cardSix();
        check(!progress.isChecklistComplete(), "A fresh checklist started complete");
        for (int index = 0; index < tasks.size() - 1; index++) {
            check(progress.complete(tasks.get(index)), "Card #6 task was rejected");
        }
        check(!progress.isChecklistComplete(), "Checklist completed with one task missing");
        check(progress.complete(tasks.get(tasks.size() - 1)), "Final task was rejected");
        check(progress.isChecklistComplete(), "All Card #6 tasks did not complete checklist");
    }

    private static void completedTasksViewIsImmutable() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardSix());
        progress.complete(BingoTask.cardSix().get(0));
        Set<BingoTask> completed = progress.completedTasks();
        try {
            completed.add(BingoTask.cardSix().get(1));
            throw new AssertionError("Completed-task view is mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: callers cannot mutate progress without complete/reset.
        }
        check(progress.completedCount() == 1, "Completed-task view leaked mutable state");
    }

    private static void resetClearsAllProgress() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardSix());
        for (BingoTask task : BingoTask.cardSix()) {
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
