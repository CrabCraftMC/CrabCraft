import { describe, expect, test } from "bun:test";
import {
  BLOCK_HUNT_CLUES,
  BLOCK_HUNT_PUZZLES,
  getBlockHuntDailyNumber,
  getBlockHuntDailyPuzzle,
  normaliseBlockGuess,
} from "../src/lib/blockHunt";
import {
  BLOCK_HUNT_BLOCKS,
  getBlockHuntBlock,
  searchBlockHuntBlocks,
} from "../src/lib/blockHuntCatalogue";
import { parseBlockHuntGlossary } from "../src/lib/blockHuntGlossary";
import {
  formatBlockHuntShare,
  formatHuntShare,
} from "../src/lib/blockHuntShare";
import {
  getHuntDailyPuzzle,
  getHuntPuzzleCount,
  HUNT_CLUES,
} from "../src/lib/hunt";
import { ITEM_HUNT_PUZZLES } from "../src/lib/itemHunt";
import { MOB_HUNT_PUZZLES } from "../src/lib/mobHunt";
import {
  getHuntCatalogue,
  getHuntEntry,
  searchHuntEntries,
  type HuntKind,
} from "../src/lib/huntCatalogue";

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
    for (const puzzle of BLOCK_HUNT_PUZZLES) {
      expect(puzzle.clues).toHaveLength(BLOCK_HUNT_CLUES);
      expect(puzzle.texture.length).toBeGreaterThan(0);
      expect(getBlockHuntBlock(puzzle.answer)).toBeDefined();
      expect(new Set(puzzle.clues.map((clue) => clue.text)).size).toBe(
        BLOCK_HUNT_CLUES,
      );
    }
  });

  test("includes the complete functionally grouped block catalogue", () => {
    expect(BLOCK_HUNT_BLOCKS.length).toBeGreaterThan(850);
    expect(getBlockHuntBlock("Composter")?.name).toBe("Composter");
    expect(getBlockHuntBlock("Red Wool")?.name).toBe("Wool");
    expect(getBlockHuntBlock("White Carpet")?.name).toBe("Carpet");
    expect(getBlockHuntBlock("Blue Stained Glass")?.name).toBe("Glass");
    expect(getBlockHuntBlock("Tinted Glass")?.name).toBe("Tinted Glass");
    expect(searchBlockHuntBlocks("red wool").map((block) => block.name)).toEqual([
      "Wool",
    ]);
    expect(new Set(BLOCK_HUNT_BLOCKS.map((block) => block.name)).size).toBe(
      BLOCK_HUNT_BLOCKS.length,
    );
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
      "Block Hunt #8 ✅\nSolved on clue 4 of 6\n3 guesses · 01:23\nhttps://crabcraft.net/games/block-hunt",
    );
  });

  test("leaves time out of an untimed shared result", () => {
    expect(
      formatBlockHuntShare({
        dailyNumber: 8,
        phase: "won",
        attemptCount: 3,
        cluesRevealed: 4,
        elapsedMs: null,
      }),
    ).toBe(
      "Block Hunt #8 ✅\nSolved on clue 4 of 6\n3 guesses\nhttps://crabcraft.net/games/block-hunt",
    );
  });

  test("uses complete stable Java catalogues from Minecraft Wiki", () => {
    const minimumEntries: Record<HuntKind, number> = {
      block: 850,
      item: 350,
      mob: 80,
    };

    for (const kind of ["block", "item", "mob"] as const) {
      const catalogue = getHuntCatalogue(kind);
      expect(catalogue.version).toBe("26.2");
      expect(catalogue.source.startsWith("https://minecraft.wiki/w/")).toBeTrue();
      expect(catalogue.entries.length).toBeGreaterThan(minimumEntries[kind]);
      expect(new Set(catalogue.entries.map((entry) => entry.name)).size).toBe(
        catalogue.entries.length,
      );
    }

    expect(getHuntEntry("item", "Diamond")?.name).toBe("Diamond");
    expect(getHuntEntry("item", "Bolt Armor Trim")?.name).toBe("Armor Trim");
    expect(getHuntEntry("item", "Red Bundle")?.name).toBe("Bundle");
    expect(getHuntEntry("item", "Blue Dye")?.name).toBe("Dye");
    expect(getHuntEntry("item", "Black Harness")?.name).toBe("Harness");
    expect(getHuntEntry("item", "Skull Pottery Sherd")?.name).toBe(
      "Pottery Sherd",
    );
    expect(getHuntEntry("item", "Thing Banner Pattern")?.name).toBe(
      "Banner Pattern",
    );
    expect(getHuntEntry("mob", "Warden")?.name).toBe("Warden");
    expect(getHuntEntry("mob", "Sulfur Cube")?.name).toBe("Sulfur Cube");
    expect(searchHuntEntries("item", "elytra")[0]?.name).toBe("Elytra");
    expect(
      searchHuntEntries("item", "wayfinder armor trim").map(
        (entry) => entry.name,
      ),
    ).toEqual(["Armor Trim"]);
    expect(
      searchHuntEntries("item", "red bundle").map((entry) => entry.name),
    ).toEqual(["Bundle"]);
  });

  test("has six guessable clues for every item and mob puzzle", () => {
    expect(getHuntPuzzleCount("item")).toBe(22);
    expect(getHuntPuzzleCount("mob")).toBe(22);

    for (const kind of ["item", "mob"] as const) {
      for (let day = 1; day <= getHuntPuzzleCount(kind); day += 1) {
        const date = new Date(Date.UTC(2026, 8, day + 1));
        const puzzle = getHuntDailyPuzzle(kind, date);
        expect(puzzle.clues).toHaveLength(HUNT_CLUES);
        expect(getHuntEntry(kind, puzzle.answer)).toBeDefined();
        expect(new Set(puzzle.clues.map((clue) => clue.text)).size).toBe(
          HUNT_CLUES,
        );
      }
    }
  });

  test("starts Item Hunt with broad clues rather than an answer fingerprint", () => {
    const puzzle = getHuntDailyPuzzle(
      "item",
      new Date("2026-09-02T12:00:00Z"),
    );

    expect(puzzle.answer).toBe("Echo Shard");
    expect(puzzle.clues[0]?.text).toBe(
      "This item has no durability and can be carried in stacks.",
    );
    expect(puzzle.clues[1]?.text).toBe(
      "Crafting is its only purpose; it has no direct use action.",
    );
    expect(
      puzzle.clues
        .slice(0, 2)
        .some((clue) =>
          clue.text.toLowerCase().includes(puzzle.answer.toLowerCase()),
        ),
    ).toBeFalse();
  });

  test("every opening Item Hunt clue applies to several prepared answers", () => {
    const openingClueCounts = new Map<string, number>();

    for (const puzzle of ITEM_HUNT_PUZZLES) {
      const openingClue = puzzle.clues[0]?.text ?? "";
      openingClueCounts.set(
        openingClue,
        (openingClueCounts.get(openingClue) ?? 0) + 1,
      );
    }

    for (const count of openingClueCounts.values()) {
      expect(count).toBeGreaterThanOrEqual(5);
    }
  });

  test("starts Mob Hunt with broad clues rather than an answer fingerprint", () => {
    const puzzle = getHuntDailyPuzzle(
      "mob",
      new Date("2026-09-02T23:00:00Z"),
    );

    expect(puzzle.answer).toBe("Shulker");
    expect(puzzle.clues[0]?.text).toBe("This mob is classified as hostile.");
    expect(puzzle.clues[1]?.text).toBe(
      "Its combat includes a ranged attack.",
    );
    expect(puzzle.clues[2]?.text).toBe(
      "Its hitbox is at least one block tall but under two blocks.",
    );
    expect(puzzle.clues.map((clue) => clue.label)).toEqual([
      "Disposition",
      "General behaviour",
      "Physical profile",
      "Distinctive trait",
      "Distinctive behaviour",
      "Identity",
    ]);
    expect(
      puzzle.clues
        .slice(0, 3)
        .some((clue) =>
          clue.text.toLowerCase().includes(puzzle.answer.toLowerCase()),
        ),
    ).toBeFalse();
  });

  test("every early Mob Hunt clue applies to several prepared answers", () => {
    for (const clueIndex of [0, 1, 2]) {
      const clueCounts = new Map<string, number>();

      for (const puzzle of MOB_HUNT_PUZZLES) {
        const clue = puzzle.clues[clueIndex]?.text ?? "";
        clueCounts.set(clue, (clueCounts.get(clue) ?? 0) + 1);
      }

      for (const count of clueCounts.values()) {
        expect(count).toBeGreaterThanOrEqual(3);
      }
    }
  });

  test("formats each hunt type with its own name and route", () => {
    expect(
      formatHuntShare({
        kind: "mob",
        dailyNumber: 8,
        phase: "won",
        attemptCount: 2,
        cluesRevealed: 3,
        elapsedMs: 42_000,
      }),
    ).toBe(
      "Mob Hunt #8 ✅\nSolved on clue 3 of 6\n2 guesses · 00:42\nhttps://crabcraft.net/games/mob-hunt",
    );
  });

  test("only marks complete glossary terms", () => {
    const parts = parseBlockHuntGlossary(
      "It gives XP, but explodes in the wrong dimension.",
    );
    const markedTerms = parts
      .filter((part) => part.definition)
      .map((part) => part.text);

    expect(markedTerms).toEqual(["XP"]);
    expect(parts.map((part) => part.text).join("")).toBe(
      "It gives XP, but explodes in the wrong dimension.",
    );
  });
});
