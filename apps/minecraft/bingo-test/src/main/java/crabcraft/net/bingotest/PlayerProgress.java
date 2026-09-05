package crabcraft.net.bingotest;

import crabcraft.net.crabUtilities.bingo.BingoTask;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Mutable, memory-only progress for one weekly-card test player. */
public final class PlayerProgress {
    private final Set<BingoTask> allowedTasks;
    private final EnumSet<BingoTask> completedTasks = EnumSet.noneOf(BingoTask.class);

    public PlayerProgress(List<BingoTask> allowedTasks) {
        Objects.requireNonNull(allowedTasks, "allowedTasks");
        this.allowedTasks = Set.copyOf(allowedTasks);
        if (allowedTasks.size() != 16 || this.allowedTasks.size() != 16) {
            throw new IllegalArgumentException("Bingo progress requires 16 unique tasks");
        }
    }

    /** Returns true only for the first accepted completion of an allowed task. */
    public boolean complete(BingoTask task) {
        Objects.requireNonNull(task, "task");
        return allowedTasks.contains(task) && completedTasks.add(task);
    }

    public boolean isComplete(BingoTask task) {
        Objects.requireNonNull(task, "task");
        return allowedTasks.contains(task) && completedTasks.contains(task);
    }

    public int completedCount() {
        return completedTasks.size();
    }

    public boolean isChecklistComplete() {
        return completedTasks.size() == allowedTasks.size();
    }

    public Set<BingoTask> completedTasks() {
        return Collections.unmodifiableSet(EnumSet.copyOf(completedTasks));
    }

    public void reset() {
        completedTasks.clear();
    }
}
