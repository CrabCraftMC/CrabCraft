import { describe, expect, test } from "bun:test";
import {
  findClosestBlocks,
  findClosestBlockAtRank,
  generateGradientOptions,
  selectGradientBlocks,
  type LabColour,
} from "../src/lib/blockGradient";
import { colorDistanceLab, hexToRgb, interpolateOklchValues, srgbToOklab } from "../src/lib/colors";
import blocks from "../src/data/blocks.json";
import blockTextureContrast from "../src/data/blockTextureContrast.json";
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

const palette = blocks
  .filter((block) => !block.id.endsWith("_glazed_terracotta"))
  .map((block) => ({
    ...block,
    lab: srgbToOklab(...hexToRgb(block.color)),
    textureContrast: blockTextureContrast[block.texture as keyof typeof blockTextureContrast],
  }));

function largestJump(blocks: TestBlock[]) {
  return Math.max(0, ...blocks.slice(1).map((block, i) => colorDistanceLab(block.lab, blocks[i].lab)));
}

function colourError(blocks: TestBlock[], start: LabColour, end: LabColour) {
  return blocks.reduce((sum, block, i) => sum + colorDistanceLab(
    block.lab, interpolateOklchValues(start, end, i / (blocks.length - 1)),
  ), 0);
}

function gradientCost(blocks: (TestBlock & { textureContrast: number })[], start: LabColour, end: LabColour) {
  return colourError(blocks, start, end) + blocks.slice(1).reduce((sum, block, i) =>
    sum + (block.textureContrast - blocks[i].textureContrast) ** 2, 0
  );
}

describe("gradient generation", () => {
  test("finds five matches in the same stable order as a full distance sort", () => {
    for (const target of palette.filter((_, i) => i % 11 === 0)) {
      const expected = [...palette].sort((a, b) =>
        colorDistanceLab(target.lab, a.lab) - colorDistanceLab(target.lab, b.lab)
      ).slice(0, 5);
      expect(findClosestBlocks(target.lab, palette, 5)).toEqual(expected);
    }
    expect(findClosestBlocks(BLOCKS[0].lab, [BLOCKS[0], { ...BLOCKS[0], id: "tie" }], 5)
      .map((block) => block.id)).toEqual(["closest", "tie"]);
  });

  test("preserves explicit endpoints, respects the candidate palette and removes repeats", () => {
    const candidates = palette.slice(0, 16);
    const start = palette.find((block) => block.id === "honeycomb_block")!;
    const end = palette.find((block) => block.id === "crimson_stem")!;
    const options = generateGradientOptions(start.lab, end.lab, candidates, start, end);
    expect(options.length).toBeGreaterThan(0);
    expect(options.length).toBeLessThanOrEqual(5);
    expect(new Set(options.map((option) => option.map((block) => block.id).join("|"))).size)
      .toBe(options.length);
    for (const option of options) {
      expect(option[0]).toBe(start);
      expect(option.at(-1)).toBe(end);
      expect(new Set(option.map((block) => block.id)).size).toBe(option.length);
      expect(option.slice(1, -1).every((block) => candidates.includes(block))).toBe(true);
    }
  });

  test("handles one available block and identical selected endpoints", () => {
    const only = palette[0];
    expect(generateGradientOptions(only.lab, only.lab, [only])).toEqual([[only]]);
    const options = generateGradientOptions(only.lab, only.lab, palette, only, only);
    expect(options).toEqual([[only]]);
    expect(selectGradientBlocks([only], 12, only.lab, only.lab)).toEqual([only]);
    expect(() => generateGradientOptions(only.lab, only.lab, [])).toThrow();
  });

  test("avoids an isolated magma texture in the honeycomb to crimson gradient", () => {
    const start = palette.find((block) => block.id === "honeycomb_block")!;
    const end = palette.find((block) => block.id === "crimson_stem")!;
    const options = generateGradientOptions(start.lab, end.lab, palette, start, end);
    const result = selectGradientBlocks(options[0], 12, start.lab, end.lab);

    expect(result.map((block) => block.id)).not.toContain("magma_block");
    expect(result.map((block) => block.id)).toContain("orange_terracotta");
    expect(result[0]).toBe(start);
    expect(result.at(-1)).toBe(end);
  });

  test("keeps deliberately selected high-contrast endpoints in either direction", () => {
    const magma = palette.find((block) => block.id === "magma_block")!;
    const crimson = palette.find((block) => block.id === "crimson_stem")!;
    for (const [start, end] of [[magma, crimson], [crimson, magma]]) {
      for (const unique of generateGradientOptions(start.lab, end.lab, palette, start, end)) {
        const result = selectGradientBlocks(unique, 3, start.lab, end.lab);
        expect(result[0]).toBe(start);
        expect(result.at(-1)).toBe(end);
      }
    }
  });

  test("prefers a consistent texture when two candidates have the same mean colour", () => {
    const candidates = [
      { id: "start", lab: [0, 0, 0] as LabColour, textureContrast: 0.02 },
      { id: "patterned", lab: [0.5, 0, 0] as LabColour, textureContrast: 0.4 },
      { id: "smooth", lab: [0.5, 0, 0] as LabColour, textureContrast: 0.02 },
      { id: "end", lab: [1, 0, 0] as LabColour, textureContrast: 0.02 },
    ];
    expect(selectGradientBlocks(candidates, 3, candidates[0].lab, candidates[3].lab)
      .map((block) => block.id)).toEqual(["start", "smooth", "end"]);
  });

  test("chooses even colour spacing instead of evenly spaced list indices", () => {
    const candidates = [0, 0.1, 0.2, 0.3, 0.5, 0.7, 1].map((l, i) => ({
      id: `${i}`, lab: [l, 0, 0] as LabColour, textureContrast: 0,
    }));
    expect(selectGradientBlocks(candidates, 4, candidates[0].lab, candidates[6].lab)
      .map((block) => block.lab[0])).toEqual([0, 0.3, 0.7, 1]);
  });

  test("minimises colour and texture cost subject to the existing largest colour jump", () => {
    // Exhaustively compare every ordered four-block subset of a small palette.
    const candidates = [0, 0.12, 0.25, 0.42, 0.68, 0.79, 1].map((l, i) => ({
      id: `${i}`, lab: [l, i % 2 ? 0.04 : 0, 0] as LabColour, textureContrast: i % 2 ? 0.15 : 0.03,
    }));
    const start = candidates[0].lab, end = candidates.at(-1)!.lab;
    const baseline = [candidates[0], candidates[2], candidates[4], candidates[6]];
    const limit = largestJump(baseline);
    let best = Infinity;
    for (let i = 1; i < 5; i++) {
      for (let j = i + 1; j < 6; j++) {
        const subset = [candidates[0], candidates[i], candidates[j], candidates[6]];
        if (largestJump(subset) <= limit + 1e-12) best = Math.min(best, gradientCost(subset, start, end));
      }
    }
    const result = selectGradientBlocks(candidates, 4, start, end);
    expect(gradientCost(result, start, end)).toBeCloseTo(best, 12);
    expect(largestJump(result)).toBeLessThanOrEqual(limit + 1e-12);
  });

  test("never worsens combined cost or the largest colour jump across real palette options", () => {
    for (let i = 0; i < 24; i++) {
      const start = palette[(i * 17) % palette.length];
      const end = palette[(i * 43 + 91) % palette.length];
      for (const unique of generateGradientOptions(start.lab, end.lab, palette, start, end)) {
        for (const steps of [3, 8, 22]) {
          const baseline = unique.length <= steps ? unique : Array.from({ length: steps }, (_, i) =>
            unique[Math.round(i / (steps - 1) * (unique.length - 1))]
          );
          const result = selectGradientBlocks(unique, steps, start.lab, end.lab);
          expect(result.length).toBe(baseline.length);
          expect(result[0]).toBe(unique[0]);
          expect(result.at(-1)).toBe(unique.at(-1));
          expect(new Set(result.map((block) => block.id)).size).toBe(result.length);
          expect(largestJump(result)).toBeLessThanOrEqual(largestJump(baseline) + 1e-12);
          if (result.length > 1) {
            expect(gradientCost(result, start.lab, end.lab))
              .toBeLessThanOrEqual(gradientCost(baseline, start.lab, end.lab) + 1e-12);
          }
        }
      }
    }
  });
});

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
