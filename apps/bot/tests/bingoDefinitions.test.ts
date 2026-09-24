import { expect, test } from "bun:test";
import { PREPARED_BINGO_CARDS } from "../src/utils/bingoDefinitions.js";

test("prepared bingo cards have sixteen distinct tasks", () => {
  for (const card of PREPARED_BINGO_CARDS) {
    expect(card.tasks).toHaveLength(16);
    expect(new Set(card.tasks.map((task) => task.id)).size).toBe(16);
  }
});
