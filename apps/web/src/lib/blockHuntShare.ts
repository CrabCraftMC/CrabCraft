import { HUNT_CLUES, HUNT_CONFIG } from "@/lib/hunt";
import type { HuntKind } from "@/lib/huntCatalogue";

type BlockHuntShareInput = {
  dailyNumber: number;
  phase: "won" | "lost";
  attemptCount: number;
  cluesRevealed: number;
  elapsedMs: number | null;
};

type HuntShareInput = BlockHuntShareInput & {
  kind: HuntKind;
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

export function formatHuntShare({
  kind,
  dailyNumber,
  phase,
  attemptCount,
  cluesRevealed,
  elapsedMs,
}: HuntShareInput): string {
  const config = HUNT_CONFIG[kind];
  const result =
    phase === "won"
      ? `Solved on clue ${cluesRevealed} of ${HUNT_CLUES}`
      : `Not solved after clue ${cluesRevealed} of ${HUNT_CLUES}`;

  return [
    `${config.name} #${dailyNumber} ${phase === "won" ? "✅" : "❌"}`,
    result,
    elapsedMs === null
      ? formatGuessCount(attemptCount)
      : `${formatGuessCount(attemptCount)} · ${formatDuration(elapsedMs)}`,
    `https://crabcraft.net${config.route}`,
  ].join("\n");
}

export function formatBlockHuntShare(input: BlockHuntShareInput): string {
  return formatHuntShare({ kind: "block", ...input });
}
