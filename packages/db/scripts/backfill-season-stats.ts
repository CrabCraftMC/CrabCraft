/**
 * One-time backfill: parse a Minecraft world's `stats/<uuid>.json` files
 * for a past season and upsert one `player_season_stats` row per player.
 * Mirrors the live ingestion path that `StatsParser.java` performs against
 * the Spigot-pushed envelope, but reads files directly off disk so old
 * worlds can be imported without spinning a server.
 *
 * Usage (from repo root):
 *     DATABASE_URL=postgresql://... \
 *       bun run packages/db/scripts/backfill-season-stats.ts \
 *         --stats-dir /path/to/season-1/world/stats \
 *         --season 1
 *
 * Idempotent: upserts on `(minecraft_uuid, season)`. Safe to re-run.
 * Assumes the matching `seasons` row already exists.
 */

import { readFileSync, readdirSync } from "node:fs";
import { resolve, basename } from "node:path";

import { sql } from "drizzle-orm";

import { db } from "../src/client";
import { playerSeasonStats, seasons } from "../src/schema";

interface ComputedStats {
  play_time_seconds: number;
  walk_distance_m: number;
  sprint_distance_m: number;
  swim_distance_m: number;
  fly_distance_m: number;
  boat_distance_m: number;
  elytra_distance_m: number;
  horse_distance_m: number;
  climb_distance_m: number;
  fall_distance_m: number;
  total_distance_m: number;
  mob_kills: number;
  player_kills: number;
  deaths: number;
  damage_dealt: number;
  damage_taken: number;
  total_blocks_mined: number;
  total_blocks_placed: number;
  total_items_crafted: number;
  total_items_broken: number;
  jumps: number;
  animals_bred: number;
  fish_caught: number;
  villagers_traded: number;
  enchantments: number;
  times_slept: number;
  top_block_mined: string | null;
  top_mob_killed: string | null;
  top_item_crafted: string | null;
  top_item_used: string | null;
  top_death_cause: string | null;
}

type StatsCategory = Record<string, number>;

function getCategory(root: Record<string, unknown>, key: string): StatsCategory {
  const v = root[key];
  return v && typeof v === "object" ? (v as StatsCategory) : {};
}

function getInt(cat: StatsCategory, key: string): number {
  const v = cat[key];
  return typeof v === "number" ? v : 0;
}

function sumCategory(cat: StatsCategory): number {
  let s = 0;
  for (const v of Object.values(cat)) s += typeof v === "number" ? v : 0;
  return s;
}

function topEntry(cat: StatsCategory): string | null {
  let topId: string | null = null;
  let topCount = 0;
  for (const [k, v] of Object.entries(cat)) {
    const n = typeof v === "number" ? v : 0;
    if (n > topCount) {
      topCount = n;
      topId = k;
    }
  }
  if (topId === null) return null;
  return JSON.stringify({ id: topId, count: topCount });
}

function parseStats(rawJson: string): ComputedStats {
  const root = JSON.parse(rawJson) as Record<string, unknown>;
  const allStats =
    root.stats && typeof root.stats === "object"
      ? (root.stats as Record<string, unknown>)
      : root;

  const custom = getCategory(allStats, "minecraft:custom");
  const mined = getCategory(allStats, "minecraft:mined");
  const crafted = getCategory(allStats, "minecraft:crafted");
  const used = getCategory(allStats, "minecraft:used");
  const killed = getCategory(allStats, "minecraft:killed");
  const killedBy = getCategory(allStats, "minecraft:killed_by");
  const broken = getCategory(allStats, "minecraft:broken");

  const walk = getInt(custom, "minecraft:walk_one_cm") / 100;
  const sprint = getInt(custom, "minecraft:sprint_one_cm") / 100;
  const swim = getInt(custom, "minecraft:swim_one_cm") / 100;
  const fly = getInt(custom, "minecraft:fly_one_cm") / 100;
  const boat = getInt(custom, "minecraft:boat_one_cm") / 100;
  const elytra = getInt(custom, "minecraft:aviate_one_cm") / 100;
  const horse = getInt(custom, "minecraft:horse_one_cm") / 100;
  const climb = getInt(custom, "minecraft:climb_one_cm") / 100;
  const fall = getInt(custom, "minecraft:fall_one_cm") / 100;

  return {
    play_time_seconds: Math.floor(getInt(custom, "minecraft:play_time") / 20),
    walk_distance_m: walk,
    sprint_distance_m: sprint,
    swim_distance_m: swim,
    fly_distance_m: fly,
    boat_distance_m: boat,
    elytra_distance_m: elytra,
    horse_distance_m: horse,
    climb_distance_m: climb,
    fall_distance_m: fall,
    total_distance_m:
      walk + sprint + swim + fly + boat + elytra + horse + climb + fall,
    mob_kills: getInt(custom, "minecraft:mob_kills"),
    player_kills: getInt(custom, "minecraft:player_kills"),
    deaths: getInt(custom, "minecraft:deaths"),
    damage_dealt: getInt(custom, "minecraft:damage_dealt"),
    damage_taken: getInt(custom, "minecraft:damage_taken"),
    total_blocks_mined: sumCategory(mined),
    total_blocks_placed: sumCategory(used),
    total_items_crafted: sumCategory(crafted),
    total_items_broken: sumCategory(broken),
    jumps: getInt(custom, "minecraft:jump"),
    animals_bred: getInt(custom, "minecraft:animals_bred"),
    fish_caught: getInt(custom, "minecraft:fish_caught"),
    villagers_traded: getInt(custom, "minecraft:traded_with_villager"),
    enchantments: getInt(custom, "minecraft:enchant_item"),
    times_slept: getInt(custom, "minecraft:sleep_in_bed"),
    top_block_mined: topEntry(mined),
    top_mob_killed: topEntry(killed),
    top_item_crafted: topEntry(crafted),
    top_item_used: topEntry(used),
    top_death_cause: topEntry(killedBy),
  };
}

function parseArgs(argv: string[]): { statsDir: string; season: string } {
  let statsDir: string | undefined;
  let season: string | undefined;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--stats-dir") statsDir = argv[++i];
    else if (a === "--season") season = argv[++i];
  }
  if (!statsDir || !season) {
    console.error(
      "Usage: bun run packages/db/scripts/backfill-season-stats.ts --stats-dir <path> --season <id>",
    );
    process.exit(1);
  }
  return { statsDir, season };
}

async function main() {
  if (!process.env.DATABASE_URL) {
    console.error("DATABASE_URL is not set");
    process.exit(1);
  }

  const { statsDir, season } = parseArgs(process.argv.slice(2));

  // Sanity-check the season row exists so we don't silently orphan rows.
  const seasonRows = await db
    .select({ id: seasons.id })
    .from(seasons)
    .where(sql`${seasons.id} = ${season}`);
  if (seasonRows.length === 0) {
    console.error(
      `Season '${season}' not found in seasons table. Insert it first:\n  INSERT INTO seasons (id, name, is_current) VALUES ('${season}', 'Season ${season}', false);`,
    );
    process.exit(1);
  }

  const resolvedDir = resolve(statsDir);
  const files = readdirSync(resolvedDir).filter(
    (f) => f.endsWith(".json") && !f.includes("Zone.Identifier"),
  );
  console.log(`Found ${files.length} stats files in ${resolvedDir}`);

  let imported = 0;
  let skipped = 0;

  for (const file of files) {
    const uuid = basename(file, ".json");
    // Skip anything that doesn't look like a UUID (8-4-4-4-12).
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(uuid)) {
      console.warn(`  Skipping non-UUID file: ${file}`);
      skipped++;
      continue;
    }

    let raw: string;
    try {
      raw = readFileSync(`${resolvedDir}/${file}`, "utf8");
    } catch (err) {
      console.warn(`  Failed to read ${file}: ${(err as Error).message}`);
      skipped++;
      continue;
    }

    let computed: ComputedStats;
    try {
      computed = parseStats(raw);
    } catch (err) {
      console.warn(`  Failed to parse ${file}: ${(err as Error).message}`);
      skipped++;
      continue;
    }

    await db
      .insert(playerSeasonStats)
      .values({
        minecraft_uuid: uuid,
        season,
        ...computed,
      })
      .onConflictDoUpdate({
        target: [playerSeasonStats.minecraft_uuid, playerSeasonStats.season],
        set: {
          ...computed,
          computed_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
        },
      });
    imported++;
    if (imported % 25 === 0) {
      process.stdout.write(`\r  ${imported}/${files.length} imported`);
    }
  }

  console.log(
    `\nDone. Imported ${imported}, skipped ${skipped} into season '${season}'.`,
  );
  process.exit(0);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
