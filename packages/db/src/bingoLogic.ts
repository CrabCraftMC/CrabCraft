export interface PositionedBingoTask {
  id: string;
}

const WINNING_LINES = [
  [0, 1, 2, 3], [4, 5, 6, 7], [8, 9, 10, 11], [12, 13, 14, 15],
  [0, 4, 8, 12], [1, 5, 9, 13], [2, 6, 10, 14], [3, 7, 11, 15],
  [0, 5, 10, 15], [3, 6, 9, 12],
] as const;

export function hasBingoLine(
  tasks: PositionedBingoTask[],
  completed: ReadonlySet<string>,
): boolean {
  return WINNING_LINES.some((line) => line.every((position) => {
    const task = tasks[position];
    return task !== undefined && completed.has(task.id);
  }));
}

export function hasBingoBlackout(
  tasks: PositionedBingoTask[],
  completed: ReadonlySet<string>,
): boolean {
  return tasks.length === 16 && tasks.every((task) => completed.has(task.id));
}
