import { describe, expect, test } from "bun:test";
import {
  PREPARED_BINGO_CARDS,
  SUPPORTED_BINGO_TASK_IDS,
  THIRD_BINGO_CARD,
} from "../src/utils/bingoDefinitions.js";

describe("prepared bingo cards", () => {
  test("prepares Bingo #3 for the next weekly window", () => {
    expect(THIRD_BINGO_CARD.number).toBe(3);
    expect(THIRD_BINGO_CARD.startsAt).toBe(1_786_953_600);
    expect(THIRD_BINGO_CARD.endsAt).toBe(1_787_526_000);
    expect(THIRD_BINGO_CARD.tasks).toHaveLength(16);
    expect(new Set(THIRD_BINGO_CARD.tasks.map((task) => task.id)).size).toBe(16);
  });

  test("advertises every prepared task as supported", () => {
    const preparedTaskIds = PREPARED_BINGO_CARDS.flatMap((card) =>
      card.tasks.map((task) => task.id),
    );

    expect(PREPARED_BINGO_CARDS.map((card) => card.number)).toEqual([1, 2, 3]);
    expect(SUPPORTED_BINGO_TASK_IDS).toEqual(new Set(preparedTaskIds));
  });
});
