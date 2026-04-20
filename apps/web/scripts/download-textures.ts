/**
 * Download block textures from Mojang's bedrock-samples repo into public/textures/blocks.
 * Only downloads textures that are missing locally, so re-runs are fast.
 *
 * Usage: bun run scripts/download-textures.ts
 */

import { readFileSync, mkdirSync, existsSync, writeFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const TEXTURE_BASE =
  "https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/textures/blocks";

interface Block {
  id: string;
  name: string;
  color: string;
  texture: string;
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
          const buffer = Buffer.from(await res.arrayBuffer());
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
}

main();
