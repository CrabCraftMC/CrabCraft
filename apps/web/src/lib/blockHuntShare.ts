import { BLOCK_HUNT_CLUES } from "@/lib/blockHunt";

type BlockHuntShareInput = {
  dailyNumber: number;
  phase: "won" | "lost";
  attemptCount: number;
  cluesRevealed: number;
  elapsedMs: number;
};

function formatDuration(milliseconds: number): string {
  const totalSeconds = Math.floor(milliseconds / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function formatGuessCount(count: number): string {
  return `${count} ${count === 1 ? "guess" : "guesses"}`;
}

function formatProgress(phase: "won" | "lost", attemptCount: number): string {
  const used = Math.min(BLOCK_HUNT_CLUES, Math.max(0, attemptCount));
  const incorrect = phase === "won" ? Math.max(0, used - 1) : used;
  const correct = phase === "won" ? 1 : 0;
  const unused = BLOCK_HUNT_CLUES - incorrect - correct;
  return `${"⬛".repeat(incorrect)}${"🟧".repeat(correct)}${"⬜".repeat(unused)}`;
}

export function formatBlockHuntShare({
  dailyNumber,
  phase,
  attemptCount,
  cluesRevealed,
  elapsedMs,
}: BlockHuntShareInput): string {
  const outcome =
    phase === "won"
      ? `Solved in ${formatGuessCount(attemptCount)}`
      : `Not solved after ${formatGuessCount(attemptCount)}`;

  return [
    `Block Hunt #${dailyNumber}`,
    formatProgress(phase, attemptCount),
    `${outcome} · ${cluesRevealed} of ${BLOCK_HUNT_CLUES} clues · ${formatDuration(elapsedMs)}`,
    "https://crabcraft.net/games/block-hunt",
  ].join("\n");
}
