package crabcraft.net.bingotest

import crabcraft.net.crabUtilities.bingo.BingoTask
import java.util.Collections
import java.util.EnumSet

/** Mutable, memory-only progress for one weekly-card test player. */
class PlayerProgress(allowedTasks: List<BingoTask>) {
    private val allowedTasks = java.util.Set.copyOf(allowedTasks)
    private val completedTasks = EnumSet.noneOf(BingoTask::class.java)

    init {
        require(allowedTasks.size == 16 && this.allowedTasks.size == 16) {
            "Bingo progress requires 16 unique tasks"
        }
    }

    /** Returns true only for the first accepted completion of an allowed task. */
    fun complete(task: BingoTask): Boolean = task in allowedTasks && completedTasks.add(task)
    fun isComplete(task: BingoTask): Boolean = task in allowedTasks && task in completedTasks
    fun completedCount(): Int = completedTasks.size
    fun isChecklistComplete(): Boolean = completedTasks.size == allowedTasks.size
    fun completedTasks(): MutableSet<BingoTask> = Collections.unmodifiableSet(EnumSet.copyOf(completedTasks))
    fun reset() { completedTasks.clear() }
}
