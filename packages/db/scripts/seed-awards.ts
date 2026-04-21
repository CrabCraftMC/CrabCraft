/**
 * Seed the `awards` table with the upstream MinecraftStats definitions.
 *
 * Reads:
 *   - packages/shared/src/awards.ts          (title, description, unit, bucket, icon)
 *   - apps/minecraft/velocity/src/main/resources/awards/*.json (reader spec)
 *
 * Idempotent: upserts on `id`, so re-running the script only
 * refreshes rows that changed upstream and leaves any DB-edited
 * fields that match a bundled default untouched. Runtime-edited
 * fields (`enabled`, `sort_order`) are preserved on re-run.
 *
 * Usage (from repo root):
 *     DATABASE_URL=postgresql://... bun run seed:awards
 */
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { sql } from "drizzle-orm";

import { db } from "../src/client";
import { awards } from "../src/schema";
import { AWARDS } from "@crabcraft/shared/awards";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
// Reader JSONs live under packages/db/seeds/awards (seed data, not
// plugin resources).
const STATS_DIR = resolve(__dirname, "..", "seeds", "awards");

interface RawReader {
  $type: string;
  path: string[];
  patterns?: string[];
}

interface RawAwardFile {
  id: string;
  unit: string;
  reader: RawReader;
}

function loadReader(id: string): RawReader {
  const path = join(STATS_DIR, `${id}.json`);
  const raw = readFileSync(path, "utf8");
  const parsed = JSON.parse(raw) as RawAwardFile;
  if (!parsed.reader || !parsed.reader.$type || !parsed.reader.path) {
    throw new Error(`Invalid reader spec in ${path}`);
  }
  return parsed.reader;
}

async function main() {
  if (!process.env.DATABASE_URL) {
    console.error("DATABASE_URL is not set");
    process.exit(1);
  }

  // Discover ids from both sources and union them so a stray file in
  // one place doesn't silently drop awards.
  const jsonIds = new Set(
    readdirSync(STATS_DIR)
      .filter((f) => f.endsWith(".json"))
      .map((f) => f.slice(0, -".json".length)),
  );
  const metaIds = new Set(Object.keys(AWARDS));

  const allIds = [...new Set([...metaIds, ...jsonIds])].sort();

  let seeded = 0;
  let sortOrder = 0;
  for (const id of allIds) {
    const meta = AWARDS[id];
    if (!meta) {
      console.warn(`skipping ${id}: no entry in shared/awards.ts`);
      continue;
    }
    if (!jsonIds.has(id)) {
      console.warn(`skipping ${id}: no reader JSON`);
      continue;
    }

    const reader = loadReader(id);

    const row = {
      id,
      title: meta.title,
      description: meta.desc ?? "",
      unit: meta.unit,
      bucket: meta.bucket,
      icon: meta.icon,
      reader_type: reader.$type,
      reader_path: reader.path,
      reader_patterns: reader.patterns ?? null,
      sort_order: sortOrder,
    };

    // Upsert on id. Preserves `enabled` across re-runs so admins can
    // disable an award without the next seed re-enabling it.
    await db
      .insert(awards)
      .values(row)
      .onConflictDoUpdate({
        target: awards.id,
        set: {
          title: row.title,
          description: row.description,
          unit: row.unit,
          bucket: row.bucket,
          icon: row.icon,
          reader_type: row.reader_type,
          reader_path: row.reader_path,
          reader_patterns: row.reader_patterns,
          updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
        },
      });

    seeded += 1;
    sortOrder += 1;
  }

  console.log(`Seeded ${seeded} awards.`);
  process.exit(0);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
