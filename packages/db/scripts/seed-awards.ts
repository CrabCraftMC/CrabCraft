/**
 * Seed the `awards` table from packages/db/seeds/awards.json.
 *
 * Idempotent: upserts on `id`. Safe to re-run; preserves the
 * runtime-editable `enabled` flag across re-runs so admins can
 * disable an award without the next seed re-enabling it.
 *
 * Usage (from repo root):
 *     DATABASE_URL=postgresql://... bun run seed:awards
 */
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { sql } from "drizzle-orm";

import { db } from "../src/client";
import { awards } from "../src/schema";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const SEED_FILE = resolve(__dirname, "..", "seeds", "awards.json");

interface SeedRow {
  id: string;
  title: string;
  description: string;
  unit: string;
  bucket: string;
  icon: string;
  reader: {
    type: string;
    path: string[];
    patterns?: string[];
  };
}

async function main() {
  if (!process.env.DATABASE_URL) {
    console.error("DATABASE_URL is not set");
    process.exit(1);
  }

  const rows = JSON.parse(readFileSync(SEED_FILE, "utf8")) as SeedRow[];
  let seeded = 0;

  for (let i = 0; i < rows.length; i++) {
    const r = rows[i];
    await db
      .insert(awards)
      .values({
        id: r.id,
        title: r.title,
        description: r.description,
        unit: r.unit,
        bucket: r.bucket,
        icon: r.icon,
        reader_type: r.reader.type,
        reader_path: r.reader.path,
        reader_patterns: r.reader.patterns ?? null,
        sort_order: i,
      })
      .onConflictDoUpdate({
        target: awards.id,
        set: {
          title: r.title,
          description: r.description,
          unit: r.unit,
          bucket: r.bucket,
          icon: r.icon,
          reader_type: r.reader.type,
          reader_path: r.reader.path,
          reader_patterns: r.reader.patterns ?? null,
          updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
        },
      });
    seeded += 1;
  }

  console.log(`Seeded ${seeded} awards from ${SEED_FILE}`);
  process.exit(0);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
