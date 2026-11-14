import { describe, expect, test } from "bun:test";
import blocks from "../src/data/blocks.json";
import { findClosestBlockAtRank } from "../src/lib/blockGradient";
import {
  colorDistanceLab,
  hexToRgb,
  interpolateOklchValues,
  oklabToSrgb,
  rgbToHex,
  srgbToOklab,
} from "../src/lib/colors";

const lab = (hex: string) => srgbToOklab(...hexToRgb(hex));

describe("OkLCH interpolation", () => {
  test("preserves near-neutral and saturated endpoints in either direction", () => {
    for (const hex of ["#373A3E", "#636965", "#E79B33"]) {
      const start = lab(hex);
      const end = lab("#611C24");

      for (const [a, b] of [[start, end], [end, start]]) {
        expect(colorDistanceLab(interpolateOklchValues(a, b, 0), a)).toBeLessThan(1e-12);
        expect(colorDistanceLab(interpolateOklchValues(a, b, 1), b)).toBeLessThan(1e-12);
      }
    }
  });

  test("keeps identical slightly tinted greys unchanged throughout a gradient", () => {
    for (const hex of ["#373a3e", "#636965"]) {
      const colour = lab(hex);

      for (const t of [0, 0.25, 0.5, 0.75, 1]) {
        const interpolated = interpolateOklchValues(colour, colour, t);
        expect(colorDistanceLab(interpolated, colour)).toBeLessThan(1e-12);
        expect(rgbToHex(...oklabToSrgb(...interpolated))).toBe(hex);
      }
    }
  });

  test("matches grey concrete exactly for a gradient of its own colour", () => {
    const palette = blocks.map((block) => ({ ...block, lab: lab(block.color) }));
    const grey = lab("#373A3E");

    expect(findClosestBlockAtRank(interpolateOklchValues(grey, grey, 0.5), palette, 0).id)
      .toBe("gray_concrete");
  });

  test("retains chroma when interpolating saturated hues", () => {
    const midpoint = interpolateOklchValues([0.6, 0.15, 0], [0.8, 0, 0.15], 0.5);

    expect(midpoint[0]).toBeCloseTo(0.7, 12);
    expect(midpoint[1]).toBeCloseTo(0.15 / Math.sqrt(2), 12);
    expect(midpoint[2]).toBeCloseTo(0.15 / Math.sqrt(2), 12);
  });

  test("uses the shortest saturated hue arc across the angle boundary", () => {
    const angle = 170 * Math.PI / 180;
    const midpoint = interpolateOklchValues(
      [0.6, 0.15 * Math.cos(angle), 0.15 * Math.sin(angle)],
      [0.8, 0.15 * Math.cos(-angle), 0.15 * Math.sin(-angle)],
      0.5,
    );

    expect(midpoint[0]).toBeCloseTo(0.7, 12);
    expect(midpoint[1]).toBeCloseTo(-0.15, 12);
    expect(midpoint[2]).toBeCloseTo(0, 12);
  });

  test("reverses the same colour path for neutral and saturated pairs", () => {
    for (const hex of ["#373A3E", "#636965", "#E79B33"]) {
      const start = lab(hex);
      const end = lab("#611C24");

      for (const t of [0, 0.25, 0.5, 0.75, 1]) {
        expect(colorDistanceLab(
          interpolateOklchValues(start, end, t),
          interpolateOklchValues(end, start, 1 - t),
        )).toBeLessThan(1e-12);
      }
    }
  });
});
