/**
 * Generate texture contrast for gradient matching from the local block PNGs.
 * Run: bun run --cwd apps/web scripts/compute-block-texture-contrast.ts
 */

import { writeFileSync } from "fs";
import { resolve } from "path";
import { fileURLToPath } from "url";
import sharp from "sharp";
import blocks from "../src/data/blocks.json";
import { srgbToOklab } from "../src/lib/colors";

/** Alpha-weighted RMS distance from the pixels' mean OKLab colour. */
export function calculateTextureContrast(rgba: Uint8Array): number {
  const mean = [0, 0, 0];
  let totalWeight = 0;
  let squaredDistance = 0;

  for (let i = 0; i < rgba.length; i += 4) {
    const weight = rgba[i + 3] / 255;
    if (weight === 0) continue;

    const lab = srgbToOklab(rgba[i], rgba[i + 1], rgba[i + 2]);
    totalWeight += weight;
    // Weighted online variance avoids subtracting nearly equal squared means.
    for (let channel = 0; channel < 3; channel++) {
      const delta = lab[channel] - mean[channel];
      mean[channel] += delta * weight / totalWeight;
      squaredDistance += weight * delta * (lab[channel] - mean[channel]);
    }
  }

  if (totalWeight === 0) throw new Error("A texture must contain visible pixels");
  return Math.sqrt(Math.max(0, squaredDistance / totalWeight));
}

async function main() {
  const textureDir = fileURLToPath(new URL("../public/textures/blocks/", import.meta.url));
  const outputPath = fileURLToPath(new URL("../src/data/blockTextureContrast.json", import.meta.url));
  const contrasts: Record<string, number> = {};

  for (const texture of new Set(blocks.map((block) => block.texture))) {
    // Include every animation frame, consistently with the catalogue colours.
    const data = await sharp(resolve(textureDir, `${texture}.png`))
      .ensureAlpha()
      .raw()
      .toBuffer();
    contrasts[texture] = Number(calculateTextureContrast(data).toFixed(6));
  }

  // Write only after every texture succeeds, preserving the previous data on failure.
  writeFileSync(outputPath, JSON.stringify(contrasts, null, 2) + "\n");
  console.log(`Written contrast for ${Object.keys(contrasts).length} textures to ${outputPath}`);
}

if (import.meta.main) await main();
