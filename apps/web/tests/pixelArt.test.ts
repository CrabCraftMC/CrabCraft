import { describe, expect, test } from "bun:test";
import {
  findNearestBlock,
  fitGridDimensions,
  makePixelArtFilename,
  mapPixelsToBlocks,
  prepareBlockPalette,
  type PixelArtBlock,
} from "../src/lib/pixelArt";

const TEST_BLOCKS: PixelArtBlock[] = [
  { id: "black", name: "Black", color: "#000000", texture: "black" },
  { id: "white", name: "White", color: "#ffffff", texture: "white" },
  { id: "red", name: "Red", color: "#ff0000", texture: "red" },
];

describe("fitGridDimensions", () => {
  test("preserves landscape, portrait, and square aspect ratios", () => {
    expect(fitGridDimensions(400, 200, 64)).toEqual({ width: 64, height: 32 });
    expect(fitGridDimensions(300, 900, 64)).toEqual({ width: 21, height: 64 });
    expect(fitGridDimensions(500, 500, 64)).toEqual({ width: 64, height: 64 });
  });

  test("clamps detail and keeps the short side visible", () => {
    expect(fitGridDimensions(2000, 1, 500)).toEqual({ width: 128, height: 1 });
    expect(fitGridDimensions(10, 10, 2)).toEqual({ width: 16, height: 16 });
  });
});

describe("block matching", () => {
  const palette = prepareBlockPalette(TEST_BLOCKS);

  test("finds an exact colour match", () => {
    expect(findNearestBlock(255, 0, 0, palette).id).toBe("red");
  });

  test("uses stable palette order for tied colours", () => {
    const tied = prepareBlockPalette([
      TEST_BLOCKS[0],
      { ...TEST_BLOCKS[0], id: "also-black" },
    ]);
    expect(findNearestBlock(0, 0, 0, tied).id).toBe("black");
  });

  test("maps opaque pixels and preserves transparent pixels as air", () => {
    const result = mapPixelsToBlocks(
      new Uint8ClampedArray([
        255, 0, 0, 255,
        0, 0, 0, 0,
      ]),
      2,
      1,
      palette,
    );

    expect(result.cells[0]?.id).toBe("red");
    expect(result.cells[1]).toBeNull();
    expect(result.blockCount).toBe(1);
    expect(result.airCount).toBe(1);
    expect(result.uniqueBlocks).toBe(1);
  });

  test("rejects invalid pixel data and empty palettes", () => {
    expect(() => mapPixelsToBlocks(new Uint8ClampedArray(3), 1, 1, palette)).toThrow();
    expect(() => mapPixelsToBlocks(new Uint8ClampedArray(4), 1, 1, [])).toThrow();
  });
});

describe("makePixelArtFilename", () => {
  test("creates a safe, descriptive PNG filename", () => {
    expect(makePixelArtFilename("My crab photo.JPG", 64, 48)).toBe(
      "my-crab-photo-64x48-block-art.png",
    );
    expect(makePixelArtFilename(".png", 16, 16)).toBe(
      "image-16x16-block-art.png",
    );
  });
});
