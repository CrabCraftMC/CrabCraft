import { describe, expect, test } from "bun:test";
import blocks from "../src/data/blocks.json";
import {
  BLOCK_HUNT_CLUES,
  BLOCK_HUNT_PUZZLES,
  getBlockHuntDailyNumber,
  getBlockHuntDailyPuzzle,
  normaliseBlockGuess,
} from "../src/lib/blockHunt";
import { formatBlockHuntShare } from "../src/lib/blockHuntShare";

describe("block hunt", () => {
  test("includes 100 distinct daily puzzles with distinct clue wording", () => {
    const answers = BLOCK_HUNT_PUZZLES.map((puzzle) => puzzle.answer);
    const clueTexts = BLOCK_HUNT_PUZZLES.flatMap((puzzle) =>
      puzzle.clues.map((clue) => clue.text),
    );

    expect(BLOCK_HUNT_PUZZLES).toHaveLength(100);
    expect(new Set(answers).size).toBe(100);
    expect(clueTexts).toHaveLength(600);
    expect(new Set(clueTexts).size).toBe(600);
  });

  test("every puzzle has six clues, a texture, and a guessable answer", () => {
    const knownBlocks = new Set(blocks.map((block) => block.name));

    for (const puzzle of BLOCK_HUNT_PUZZLES) {
      expect(puzzle.clues).toHaveLength(BLOCK_HUNT_CLUES);
      expect(puzzle.texture.length).toBeGreaterThan(0);
      expect(knownBlocks.has(puzzle.answer)).toBe(true);
      expect(new Set(puzzle.clues.map((clue) => clue.text)).size).toBe(
        BLOCK_HUNT_CLUES,
      );
    }
  });

  test("normalises harmless spacing and case differences", () => {
    expect(normaliseBlockGuess("  Creaking   HEART ")).toBe("creaking heart");
  });

  test("daily selection starts at puzzle one and advances predictably", () => {
    const firstDay = new Date("2026-09-02T12:00:00Z");
    const secondDay = new Date("2026-09-03T12:00:00Z");

    expect(getBlockHuntDailyNumber(firstDay)).toBe(1);
    expect(getBlockHuntDailyNumber(secondDay)).toBe(2);
    expect(getBlockHuntDailyPuzzle(firstDay)).toBe(BLOCK_HUNT_PUZZLES[0]);
    expect(getBlockHuntDailyPuzzle(secondDay)).toBe(BLOCK_HUNT_PUZZLES[1]);
  });

  test("formats a spoiler-free result for sharing", () => {
    expect(
      formatBlockHuntShare({
        dailyNumber: 8,
        phase: "won",
        attemptCount: 3,
        cluesRevealed: 4,
        elapsedMs: 83_000,
      }),
    ).toBe(
      "Block Hunt #8\n⬛⬛🟧⬜⬜⬜\nSolved in 3 guesses · 4 of 6 clues · 01:23\nhttps://crabcraft.net/tools/block-hunt",
    );
  });
});
