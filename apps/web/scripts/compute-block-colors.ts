/**
 * Compute accurate average colors for all blocks by reading the local
 * texture PNGs (under public/textures/blocks) and averaging pixel colors
 * in linear RGB space (gamma-corrected). Local files are used so any
 * download-time post-processing (e.g. biome tinting in download-textures.ts)
 * is reflected in the computed colors.
 *
 * Run scripts/download-textures.ts first to ensure textures are present.
 *
 * Usage: bun run scripts/compute-block-colors.ts
 */

import sharp from "sharp";
import { readFileSync, writeFileSync, existsSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const TEXTURE_DIR = resolve(
  fileURLToPath(import.meta.url),
  "..",
  "..",
  "public",
  "textures",
  "blocks"
);

interface Block {
  id: string;
  name: string;
  color: string;
  texture: string;
}

// sRGB to linear RGB component (gamma removal)
function srgbToLinear(c: number): number {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

// Linear RGB to sRGB component (0-255)
function linearToSrgb(c: number): number {
  const s =
    c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
  return Math.round(Math.min(255, Math.max(0, s * 255)));
}

function rgbToHex(r: number, g: number, b: number): string {
  return (
    "#" +
    [r, g, b]
      .map((v) => v.toString(16).padStart(2, "0"))
      .join("")
      .toUpperCase()
  );
}

async function computeAverageColor(
  texturePath: string
): Promise<string | null> {
  try {
    if (!existsSync(texturePath)) return null;

    const { data } = await sharp(texturePath)
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });

    let sumR = 0,
      sumG = 0,
      sumB = 0,
      totalWeight = 0;

    for (let i = 0; i < data.length; i += 4) {
      const a = data[i + 3];
      if (a === 0) continue; // Skip fully transparent pixels

      // Weight by alpha so semi-transparent pixels contribute proportionally
      const weight = a / 255;
      sumR += srgbToLinear(data[i]) * weight;
      sumG += srgbToLinear(data[i + 1]) * weight;
      sumB += srgbToLinear(data[i + 2]) * weight;
      totalWeight += weight;
    }

    if (totalWeight === 0) return null;

    // Average in linear space, convert back to sRGB
    const r = linearToSrgb(sumR / totalWeight);
    const g = linearToSrgb(sumG / totalWeight);
    const b = linearToSrgb(sumB / totalWeight);

    return rgbToHex(r, g, b);
  } catch {
    return null;
  }
}

async function main() {
  const blocksPath = resolve(__dirname, "..", "src", "data", "blocks.json");
  const blocks: Block[] = JSON.parse(readFileSync(blocksPath, "utf-8"));

  console.log(`Processing ${blocks.length} blocks...\n`);

  let updated = 0;
  let failed = 0;

  // Process in batches of 10
  const BATCH_SIZE = 10;
  for (let i = 0; i < blocks.length; i += BATCH_SIZE) {
    const batch = blocks.slice(i, i + BATCH_SIZE);
    const results = await Promise.all(
      batch.map(async (block) => {
        const path = resolve(TEXTURE_DIR, `${block.texture}.png`);
        const color = await computeAverageColor(path);
        return { block, color };
      })
    );

    for (const { block, color } of results) {
      if (color) {
        if (block.color !== color) {
          console.log(`  ${block.id}: ${block.color} -> ${color}`);
          block.color = color;
          updated++;
        }
      } else {
        console.warn(`  FAILED: ${block.id} (${block.texture})`);
        failed++;
      }
    }

    process.stdout.write(
      `\r  ${Math.min(i + BATCH_SIZE, blocks.length)}/${blocks.length} processed`
    );
  }

  console.log(`\n\nDone! ${updated} colors updated, ${failed} failed.`);

  writeFileSync(blocksPath, JSON.stringify(blocks, null, 2) + "\n");
  console.log(`Written to ${blocksPath}`);
}

main();
