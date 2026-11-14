import { describe, expect, test } from "bun:test";
import { fileURLToPath } from "url";
import sharp from "sharp";
import { calculateTextureContrast } from "../scripts/compute-block-texture-contrast";
import blocks from "../src/data/blocks.json";
import textureContrast from "../src/data/blockTextureContrast.json";
import { colorDistanceLab, srgbToOklab } from "../src/lib/colors";

describe("block texture contrast", () => {
  test("a solid texture has no contrast", () => {
    expect(calculateTextureContrast(new Uint8Array([
      127, 80, 32, 255,
      127, 80, 32, 255,
      127, 80, 32, 255,
    ]))).toBe(0);
  });

  test("two equally weighted colours have half their OKLab distance as RMS contrast", () => {
    const expected = colorDistanceLab(srgbToOklab(0, 0, 0), srgbToOklab(255, 255, 255)) / 2;
    expect(calculateTextureContrast(new Uint8Array([
      0, 0, 0, 255,
      255, 255, 255, 255,
    ]))).toBeCloseTo(expected, 12);
  });

  test("weights partial alpha and ignores fully transparent pixels", () => {
    const distance = colorDistanceLab(srgbToOklab(0, 0, 0), srgbToOklab(255, 255, 255));
    // White has one quarter of the total weight: variance = p(1-p) × distance².
    expect(calculateTextureContrast(new Uint8Array([
      0, 0, 0, 255,
      255, 255, 255, 85,
      255, 0, 0, 0,
    ]))).toBeCloseTo(Math.sqrt(3) / 4 * distance, 12);
  });

  test("rejects a texture without visible pixels", () => {
    expect(() => calculateTextureContrast(new Uint8Array([255, 0, 0, 0])))
      .toThrow("A texture must contain visible pixels");
  });

  test("the generated catalogue covers every texture with finite non-negative contrast", () => {
    expect(Object.keys(textureContrast).sort()).toEqual([...new Set(blocks.map((block) => block.texture))].sort());
    for (const contrast of Object.values(textureContrast)) {
      expect(Number.isFinite(contrast)).toBe(true);
      expect(contrast).toBeGreaterThanOrEqual(0);
    }
  });

  test("generated values match static, transparent and animated PNGs", async () => {
    for (const id of ["white_concrete", "orange_stained_glass", "magma_block", "crimson_stem"]) {
      const block = blocks.find((block) => block.id === id)!;
      const path = fileURLToPath(new URL(`../public/textures/blocks/${block.texture}.png`, import.meta.url));
      const rgba = await sharp(path).ensureAlpha().raw().toBuffer();
      const contrast = textureContrast[block.texture as keyof typeof textureContrast];
      expect(contrast).toBe(Number(calculateTextureContrast(rgba).toFixed(6)));
    }
  });
});
