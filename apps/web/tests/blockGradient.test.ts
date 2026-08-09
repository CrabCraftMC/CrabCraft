import { describe, expect, test } from "bun:test";
import {
  findClosestBlockAtRank,
  type LabColour,
} from "../src/lib/blockGradient";
import {
  normaliseBlockGradientShareState,
  type BlockGradientShareState,
} from "../src/lib/blockGradientShare";

type TestBlock = {
  id: string;
  lab: LabColour;
};

const BLOCKS: TestBlock[] = [
  { id: "closest", lab: [0.5, 0, 0] },
  { id: "second", lab: [0.6, 0, 0] },
  { id: "third", lab: [0.7, 0, 0] },
];

describe("findClosestBlockAtRank", () => {
  test("returns ranked alternatives in perceptual-distance order", () => {
    const target: LabColour = [0.51, 0, 0];

    expect(findClosestBlockAtRank(target, BLOCKS, 0).id).toBe("closest");
    expect(findClosestBlockAtRank(target, BLOCKS, 1).id).toBe("second");
    expect(findClosestBlockAtRank(target, BLOCKS, 2).id).toBe("third");
  });

  test("keeps source order for tied matches", () => {
    const tied = [
      { id: "first", lab: [0.4, 0, 0] as LabColour },
      { id: "second", lab: [0.6, 0, 0] as LabColour },
    ];

    expect(findClosestBlockAtRank([0.5, 0, 0], tied, 0).id).toBe("first");
    expect(findClosestBlockAtRank([0.5, 0, 0], tied, 1).id).toBe("second");
  });

  test("clamps invalid ranks and rejects an empty palette", () => {
    expect(findClosestBlockAtRank([0.51, 0, 0], BLOCKS, -1).id).toBe("closest");
    expect(findClosestBlockAtRank([0.51, 0, 0], BLOCKS, 99).id).toBe("third");
    expect(() => findClosestBlockAtRank([0.5, 0, 0], [], 0)).toThrow();
  });
});

describe("Block Gradient shares", () => {
  const state: BlockGradientShareState = {
    start: {
      mode: "block",
      color: "#E79B33",
      blockId: "honeycomb_block",
    },
    end: {
      mode: "color",
      color: "#123ABC",
      blockId: null,
    },
    steps: 14,
    randomness: 35,
    gradientLength: 11,
    paletteOption: 3,
    blockPresets: ["no_transparent", "survival"],
    excludedIds: ["stone", "dirt"],
  };

  test("normalises a complete recipe for storage", () => {
    expect(normaliseBlockGradientShareState(state)).toEqual(state);
  });

  test("rejects invalid bounds, blocks, and presets", () => {
    expect(normaliseBlockGradientShareState({ ...state, steps: 99 })).toBeNull();
    expect(normaliseBlockGradientShareState({
      ...state,
      start: { ...state.start, blockId: "not_a_real_block" },
    })).toBeNull();
    expect(normaliseBlockGradientShareState({
      ...state,
      blockPresets: ["not_a_real_preset"],
    })).toBeNull();
  });

  test("rejects duplicate, malformed, and endpoint exclusions", () => {
    expect(normaliseBlockGradientShareState({
      ...state,
      excludedIds: ["stone", "stone"],
    })).toBeNull();
    expect(normaliseBlockGradientShareState({
      ...state,
      excludedIds: ["<script>"],
    })).toBeNull();
    expect(normaliseBlockGradientShareState({
      ...state,
      excludedIds: ["honeycomb_block"],
    })).toBeNull();
  });
});
