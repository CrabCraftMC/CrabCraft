package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small dependency-free regression test; run this class with assertions enabled. */
public final class PlayerProgressTest {
    private static final List<String> EXPECTED_IDS = List.of(
            "shear_bogged",
            "ring_bell_projectile",
            "berry_bush_kill",
            "dry_sponge_nether",
            "five_parrots_dance",
            "detonate_tnt_minecart",
            "target_opens_door",
            "collect_turtle_scute",
            "shelf_hotbar_swap",
            "remove_pig_saddle",
            "explorer_map_trade",
            "self_arrow_totem",
            "equip_piglin_gold_armour",
            "leashed_bee_sting",
            "creeper_rings_bell",
            "mine_copper_golem_statue");

    private PlayerProgressTest() {}

    public static void main(String[] args) {
        cardTwoIdsAreUniqueAndInOrder();
        constructorRequiresExactlySixteenUniqueTasks();
        constructorDefensivelyCopiesAllowedTasks();
        directCompletionIsIdempotent();
        cardOneCompletionIsRejected();
        checklistCompletesOnlyAfterAllCardTwoTasks();
        completedTasksViewIsImmutable();
        resetClearsAllProgress();
    }

    private static void cardTwoIdsAreUniqueAndInOrder() {
        List<BingoTask> tasks = BingoTask.cardTwo();
        check(tasks.stream().map(BingoTask::id).toList().equals(EXPECTED_IDS),
                "Bingo #2 task order changed");
        check(new HashSet<>(tasks).size() == 16, "Bingo #2 tasks must be unique");

        Set<String> ids = new HashSet<>();
        for (BingoTask task : tasks) {
            check(ids.add(task.id()), "Duplicate task ID " + task.id());
            check(BingoTask.fromId(task.id()).orElseThrow() == task, "Task ID lookup failed");
        }
    }

    private static void constructorRequiresExactlySixteenUniqueTasks() {
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.cardTwo().subList(0, 15)),
                "A 15-task test card was accepted");
        expectIllegalArgument(
                () -> new PlayerProgress(BingoTask.allDeployed()),
                "A 32-task deployed catalogue was accepted as one test card");

        List<BingoTask> duplicated = new ArrayList<>(BingoTask.cardTwo());
        duplicated.set(duplicated.size() - 1, duplicated.get(0));
        expectIllegalArgument(
                () -> new PlayerProgress(duplicated),
                "A Card #2 task list containing a duplicate was accepted");
    }

    private static void constructorDefensivelyCopiesAllowedTasks() {
        List<BingoTask> mutableTasks = new ArrayList<>(BingoTask.cardTwo());
        PlayerProgress progress = new PlayerProgress(mutableTasks);
        BingoTask first = mutableTasks.get(0);
        mutableTasks.clear();

        check(progress.complete(first), "Mutating the constructor input changed allowed tasks");
        check(progress.completedCount() == 1, "Defensive-copy test recorded the wrong total");
    }

    private static void directCompletionIsIdempotent() {
        BingoTask task = BingoTask.cardTwo().get(0);
        PlayerProgress progress = new PlayerProgress(BingoTask.cardTwo());
        check(progress.complete(task), "First completion should be new");
        check(!progress.complete(task), "Repeated completion should be ignored");
        check(progress.completedCount() == 1, "Repeated completion changed the total");
    }

    private static void cardOneCompletionIsRejected() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardTwo());
        BingoTask cardOneTask = BingoTask.cardOne().get(0);
        check(!progress.complete(cardOneTask), "A Card #1 task was accepted");
        check(!progress.isComplete(cardOneTask), "A Card #1 task appears complete");
        check(progress.completedCount() == 0, "A Card #1 task changed the total");
    }

    private static void checklistCompletesOnlyAfterAllCardTwoTasks() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardTwo());
        List<BingoTask> tasks = BingoTask.cardTwo();
        check(!progress.isChecklistComplete(), "A fresh checklist started complete");
        for (int index = 0; index < tasks.size() - 1; index++) {
            check(progress.complete(tasks.get(index)), "Card #2 task was rejected");
        }
        check(!progress.isChecklistComplete(), "Checklist completed with one task missing");
        check(progress.complete(tasks.get(tasks.size() - 1)), "Final task was rejected");
        check(progress.isChecklistComplete(), "All Card #2 tasks did not complete checklist");
    }

    private static void completedTasksViewIsImmutable() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardTwo());
        progress.complete(BingoTask.cardTwo().get(0));
        Set<BingoTask> completed = progress.completedTasks();
        try {
            completed.add(BingoTask.cardTwo().get(1));
            throw new AssertionError("Completed-task view is mutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: callers cannot mutate progress without complete/reset.
        }
        check(progress.completedCount() == 1, "Completed-task view leaked mutable state");
    }

    private static void resetClearsAllProgress() {
        PlayerProgress progress = new PlayerProgress(BingoTask.cardTwo());
        for (BingoTask task : BingoTask.cardTwo()) {
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
