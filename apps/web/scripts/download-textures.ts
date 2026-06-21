/**
 * Download block textures from Mojang's bedrock-samples repo into public/textures/blocks,
 * AND the Wrapped story-flow texture set into public/minecraft.
 *
 * Only downloads textures that are missing locally, so re-runs are fast.
 *
 * Usage: bun run scripts/download-textures.ts
 */

import { readFileSync, mkdirSync, existsSync, writeFileSync, copyFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";
import sharp from "sharp";
import { REQUIRED_MC_TEXTURES, POPULAR_TOP_BLOCKS } from "../src/lib/minecraftTextures";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const TEXTURE_BASE =
  "https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/textures/blocks";

const MC_TEXTURE_BASE =
  "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/26.2/assets/minecraft/textures";

// Java edition occasionally lacks the exact filename the Wrapped manifest asks
// for and we want a sensible visual substitute.
const JAVA_PATH_OVERRIDES: Record<string, string> = {
  "block/ancient_debris.png": "block/ancient_debris_side.png",
  "item/crossbow.png": "item/crossbow_standby.png",
};

const EXTERNAL_MC_ROOT = resolve(
  __dirname,
  "..",
  "..",
  "..",
  "_external",
  "crabcraft-wrapped-main",
  "public",
  "minecraft"
);

interface Block {
  id: string;
  name: string;
  color: string;
  texture: string;
}

// Mojang ships leaves as grayscale PNGs intended for runtime biome tinting.
// Bake a representative tint into the local PNG so the static-color pipeline
// (compute-block-colors.ts) and the picker thumbnails show real green leaves.
// Tints are linear-RGB multipliers (sRGB hex → linear when applied).
const BIOME_TINTS: Record<string, string> = {
  leaves_oak_opaque: "#77AB2F",
  leaves_jungle_opaque: "#59C93C",
  leaves_acacia_opaque: "#77AB2F",
  leaves_big_oak_opaque: "#59AE30",
  leaves_spruce_opaque: "#619961",
  leaves_birch_opaque: "#80A755",
  mangrove_leaves_opaque: "#71A047",
};


function srgbToLinear(c: number): number {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

function linearToSrgb(c: number): number {
  const s =
    c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
  return Math.round(Math.min(255, Math.max(0, s * 255)));
}

async function applyBiomeTint(buffer: Buffer<ArrayBufferLike>, hexTint: string): Promise<Buffer> {
  const r = parseInt(hexTint.slice(1, 3), 16);
  const g = parseInt(hexTint.slice(3, 5), 16);
  const b = parseInt(hexTint.slice(5, 7), 16);
  const tR = srgbToLinear(r);
  const tG = srgbToLinear(g);
  const tB = srgbToLinear(b);

  const { data, info } = await sharp(buffer)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const out = Buffer.from(data);
  for (let i = 0; i < out.length; i += 4) {
    out[i] = linearToSrgb(srgbToLinear(out[i]) * tR);
    out[i + 1] = linearToSrgb(srgbToLinear(out[i + 1]) * tG);
    out[i + 2] = linearToSrgb(srgbToLinear(out[i + 2]) * tB);
  }
  return await sharp(out, {
    raw: { width: info.width, height: info.height, channels: 4 },
  })
    .png()
    .toBuffer();
}

async function main() {
  const blocksPath = resolve(__dirname, "..", "src", "data", "blocks.json");
  const outDir = resolve(__dirname, "..", "public", "textures", "blocks");
  const blocks: Block[] = JSON.parse(readFileSync(blocksPath, "utf-8"));

  // Collect unique texture paths (some blocks may share textures)
  const textures = [...new Set(blocks.map((b) => b.texture))];

  let downloaded = 0;
  let skipped = 0;
  let failed = 0;

  const BATCH_SIZE = 20;
  for (let i = 0; i < textures.length; i += BATCH_SIZE) {
    const batch = textures.slice(i, i + BATCH_SIZE);
    await Promise.all(
      batch.map(async (texture) => {
        const outPath = resolve(outDir, `${texture}.png`);

        // Skip if already downloaded
        if (existsSync(outPath)) {
          skipped++;
          return;
        }

        try {
          const url = `${TEXTURE_BASE}/${texture}.png`;
          const res = await fetch(url);
          if (!res.ok) {
            console.warn(`  FAILED: ${texture} (${res.status})`);
            failed++;
            return;
          }

          // Ensure subdirectory exists (some textures have paths like "deepslate/...")
          mkdirSync(dirname(outPath), { recursive: true });
          let buffer: Buffer<ArrayBufferLike> = Buffer.from(await res.arrayBuffer());
          if (BIOME_TINTS[texture]) {
            buffer = await applyBiomeTint(buffer, BIOME_TINTS[texture]);
          }
          writeFileSync(outPath, buffer);
          downloaded++;
        } catch {
          console.warn(`  FAILED: ${texture}`);
          failed++;
        }
      })
    );

    process.stdout.write(
      `\r  ${Math.min(i + BATCH_SIZE, textures.length)}/${textures.length} checked`
    );
  }

  console.log(
    `\n\nDone! ${downloaded} downloaded, ${skipped} already existed, ${failed} failed.`
  );

  await downloadWrappedTextures();
}

/**
 * Download textures needed by the Wrapped story flow into public/minecraft/.
 * Prefers copying from _external/crabcraft-wrapped-main/public/minecraft/ when
 * the reference repo is available locally; otherwise fetches from Mojang's
 * bedrock-samples. Animated item strips (clock_16.png, compass_16.png) are
 * cropped to their first 16×16 frame via sharp.
 */
async function downloadWrappedTextures() {
  const outRoot = resolve(__dirname, "..", "public", "minecraft");
  const paths = [
    ...REQUIRED_MC_TEXTURES,
    ...POPULAR_TOP_BLOCKS.flatMap((b) => [`block/${b}.png`]),
  ];

  let downloaded = 0;
  let skipped = 0;
  let copied = 0;
  let failed = 0;

  console.log(`\nChecking ${paths.length} story textures...`);

  for (const path of paths) {
    const outPath = resolve(outRoot, path);
    if (existsSync(outPath)) {
      skipped++;
      continue;
    }
    mkdirSync(dirname(outPath), { recursive: true });

    // 1. Try to copy from the reference repo if present.
    const externalPath = resolve(EXTERNAL_MC_ROOT, path);
    if (existsSync(externalPath)) {
      try {
        copyFileSync(externalPath, outPath);
        copied++;
        continue;
      } catch {
        // fall through to fetch
      }
    }

    // 2. Fetch from the Java-edition asset CDN. Animated items like clock /
    //    compass ship as per-frame PNGs (clock_00.png … clock_63.png), so the
    //    manifest's `clock_16.png` is a literal upstream filename — no crop.
    const fetchPath = JAVA_PATH_OVERRIDES[path] ?? path;
    const url = `${MC_TEXTURE_BASE}/${fetchPath}`;
    try {
      const res = await fetch(url);
      if (!res.ok) {
        console.warn(`  FAILED: ${path} (${res.status})`);
        failed++;
        continue;
      }
      const buffer = Buffer.from(await res.arrayBuffer());
      writeFileSync(outPath, buffer);
      downloaded++;
    } catch {
      console.warn(`  FAILED: ${path}`);
      failed++;
    }
  }

  console.log(
    `  Story textures: ${downloaded} downloaded, ${copied} copied from reference, ${skipped} already existed, ${failed} failed.`
  );
}

main();
