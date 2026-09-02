import { BLOCK_HUNT_CLUES } from "@/lib/blockHunt";

type BlockHuntShareInput = {
  dailyNumber: number;
  phase: "won" | "lost";
  attemptCount: number;
  cluesRevealed: number;
  elapsedMs: number | null;
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

export function formatBlockHuntShare({
  dailyNumber,
  phase,
  attemptCount,
  cluesRevealed,
  elapsedMs,
}: BlockHuntShareInput): string {
  const result =
    phase === "won"
      ? `Solved on clue ${cluesRevealed} of ${BLOCK_HUNT_CLUES}`
      : `Not solved after clue ${cluesRevealed} of ${BLOCK_HUNT_CLUES}`;

  return [
    `Block Hunt #${dailyNumber} ${phase === "won" ? "✅" : "❌"}`,
    result,
    elapsedMs === null
      ? formatGuessCount(attemptCount)
      : `${formatGuessCount(attemptCount)} · ${formatDuration(elapsedMs)}`,
    "https://crabcraft.net/games/block-hunt",
  ].join("\n");
}
