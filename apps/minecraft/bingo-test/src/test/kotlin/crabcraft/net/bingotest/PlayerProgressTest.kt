package crabcraft.net.bingotest

import crabcraft.net.crabUtilities.bingo.BingoTask

/** Small dependency-free regression test. */
object PlayerProgressTest {
    @JvmStatic
    fun main(args: Array<String>) {
        constructorRequiresExactlySixteenUniqueTasks()
        constructorDefensivelyCopiesAllowedTasks()
        directCompletionIsIdempotent()
        earlierCardCompletionIsRejected()
        checklistCompletesOnlyAfterAllCardSixTasks()
        completedTasksViewIsImmutable()
        resetClearsAllProgress()
    }

    private fun constructorRequiresExactlySixteenUniqueTasks() {
        expectIllegalArgument("A 15-task test card was accepted") { PlayerProgress(BingoTask.cardSix().subList(0, 15)) }
        expectIllegalArgument("The deployed catalogue was accepted as one test card") {
            PlayerProgress(BingoTask.allDeployed())
        }
        val duplicated = BingoTask.cardSix().toMutableList()
        duplicated[duplicated.lastIndex] = duplicated[0]
        expectIllegalArgument("A Card #6 task list containing a duplicate was accepted") { PlayerProgress(duplicated) }
    }

    private fun constructorDefensivelyCopiesAllowedTasks() {
        val mutableTasks = BingoTask.cardSix().toMutableList()
        val progress = PlayerProgress(mutableTasks)
        val first = mutableTasks[0]
        mutableTasks.clear()
        check(progress.complete(first), "Mutating the constructor input changed allowed tasks")
        check(progress.completedCount() == 1, "Defensive-copy test recorded the wrong total")
    }

    private fun directCompletionIsIdempotent() {
        val task = BingoTask.cardSix()[0]
        val progress = PlayerProgress(BingoTask.cardSix())
        check(progress.complete(task), "First completion should be new")
        check(!progress.complete(task), "Repeated completion should be ignored")
        check(progress.completedCount() == 1, "Repeated completion changed the total")
    }

    private fun earlierCardCompletionIsRejected() {
        val progress = PlayerProgress(BingoTask.cardSix())
        for (earlierTask in
            listOf(
                BingoTask.cardOne()[0],
                BingoTask.cardTwo()[0],
                BingoTask.cardThree()[0],
                BingoTask.cardFour()[0],
                BingoTask.cardFive()[0],
            )) {
            check(!progress.complete(earlierTask), "An earlier-card task was accepted")
            check(!progress.isComplete(earlierTask), "An earlier-card task appears complete")
        }
        check(progress.completedCount() == 0, "An earlier-card task changed the total")
    }

    private fun checklistCompletesOnlyAfterAllCardSixTasks() {
        val progress = PlayerProgress(BingoTask.cardSix())
        val tasks = BingoTask.cardSix()
        check(!progress.isChecklistComplete(), "A fresh checklist started complete")
        tasks.dropLast(1).forEach { check(progress.complete(it), "Card #6 task was rejected") }
        check(!progress.isChecklistComplete(), "Checklist completed with one task missing")
        check(progress.complete(tasks.last()), "Final task was rejected")
        check(progress.isChecklistComplete(), "All Card #6 tasks did not complete checklist")
    }

    private fun completedTasksViewIsImmutable() {
        val progress = PlayerProgress(BingoTask.cardSix())
        progress.complete(BingoTask.cardSix()[0])
        val completed = progress.completedTasks()
        try {
            (completed as MutableSet<BingoTask>).add(BingoTask.cardSix()[1])
            throw AssertionError("Completed-task view is mutable")
        } catch (_: UnsupportedOperationException) {
            // Callers cannot mutate progress without complete/reset.
        }
        check(progress.completedCount() == 1, "Completed-task view leaked mutable state")
    }

    private fun resetClearsAllProgress() {
        val progress = PlayerProgress(BingoTask.cardSix())
        BingoTask.cardSix().forEach { progress.complete(it) }
        progress.reset()
        check(progress.completedCount() == 0, "Reset did not clear completed tasks")
        check(progress.completedTasks().isEmpty(), "Reset left completed tasks behind")
        check(!progress.isChecklistComplete(), "Reset checklist remained complete")
    }

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private fun expectIllegalArgument(message: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError(message)
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
