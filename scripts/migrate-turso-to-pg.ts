/**
 * One-time migration script: Turso (LibSQL) → PostgreSQL
 *
 * Usage:
 *   TURSO_URL=libsql://... TURSO_AUTH_TOKEN=... DATABASE_URL=postgresql://... bun run scripts/migrate-turso-to-pg.ts
 */

import { createClient } from "@libsql/client";
import postgres from "postgres";

const turso = createClient({
  url: process.env.TURSO_URL!,
  authToken: process.env.TURSO_AUTH_TOKEN!,
});

const pg = postgres(process.env.DATABASE_URL!);

async function migrateUsers() {
  const result = await turso.execute("SELECT * FROM users");
  const rows = result.rows;
  if (rows.length === 0) {
    console.log("  users: 0 rows (empty)");
    return 0;
  }

  for (const row of rows) {
    await pg`
      INSERT INTO users (discord_id, discord_username, minecraft_username, minecraft_uuid, active, created_at, updated_at, last_login_at, is_admin)
      VALUES (
        ${row.discord_id as string},
        ${row.discord_username as string},
        ${(row.minecraft_username as string | null) ?? null},
        ${(row.minecraft_uuid as string | null) ?? null},
        ${Boolean(row.active)},
        ${row.created_at as number},
        ${row.updated_at as number},
        ${(row.last_login_at as number | null) ?? null},
        ${Boolean(row.is_admin)}
      )
      ON CONFLICT (discord_id) DO NOTHING
    `;
  }
  console.log(`  users: ${rows.length} rows migrated`);
  return rows.length;
}

async function migrateApplications() {
  const result = await turso.execute("SELECT * FROM applications");
  const rows = result.rows;
  if (rows.length === 0) {
    console.log("  applications: 0 rows (empty)");
    return 0;
  }

  for (const row of rows) {
    await pg`
      INSERT INTO applications (id, discord_id, discord_username, minecraft_username, minecraft_uuid, over_15, voice_chat, policy_agreed, status, join_reason, favourite_wood, denial_reason, season, applied_at, resolved_at, resolved_by_discord_id)
      VALUES (
        ${row.id as number},
        ${row.discord_id as string},
        ${row.discord_username as string},
        ${row.minecraft_username as string},
        ${(row.minecraft_uuid as string | null) ?? null},
        ${Boolean(row.over_15)},
        ${Boolean(row.voice_chat)},
        ${Boolean(row.policy_agreed)},
        ${row.status as string},
        ${(row.join_reason as string | null) ?? null},
        ${(row.favourite_wood as string | null) ?? null},
        ${(row.denial_reason as string | null) ?? null},
        ${(row.season as string | null) ?? null},
        ${row.applied_at as number},
        ${(row.resolved_at as number | null) ?? null},
        ${(row.resolved_by_discord_id as string | null) ?? null}
      )
      ON CONFLICT (id) DO NOTHING
    `;
  }

  // Reset the serial sequence to the max id
  await pg`SELECT setval('applications_id_seq', (SELECT COALESCE(MAX(id), 0) FROM applications))`;

  console.log(`  applications: ${rows.length} rows migrated`);
  return rows.length;
}

async function migrateSeasons() {
  const result = await turso.execute("SELECT * FROM seasons");
  const rows = result.rows;
  if (rows.length === 0) {
    console.log("  seasons: 0 rows (empty)");
    return 0;
  }

  for (const row of rows) {
    await pg`
      INSERT INTO seasons (id, name, start_date, end_date, is_current, created_at)
      VALUES (
        ${row.id as string},
        ${row.name as string},
        ${(row.start_date as string | null) ?? null},
        ${(row.end_date as string | null) ?? null},
        ${Boolean(row.is_current)},
        ${row.created_at as number}
      )
      ON CONFLICT (id) DO NOTHING
    `;
  }
  console.log(`  seasons: ${rows.length} rows migrated`);
  return rows.length;
}

async function migratePlayerSeasonStats() {
  const result = await turso.execute("SELECT * FROM player_season_stats");
  const rows = result.rows;
  if (rows.length === 0) {
    console.log("  player_season_stats: 0 rows (empty)");
    return 0;
  }

  for (const row of rows) {
    await pg`
      INSERT INTO player_season_stats (
        id, minecraft_uuid, season,
        play_time_seconds, walk_distance_m, sprint_distance_m,
        swim_distance_m, fly_distance_m, boat_distance_m,
        elytra_distance_m, horse_distance_m, climb_distance_m,
        fall_distance_m, total_distance_m,
        mob_kills, player_kills, deaths,
        damage_dealt, damage_taken,
        total_blocks_mined, total_blocks_placed,
        total_items_crafted, total_items_broken,
        jumps, animals_bred, fish_caught,
        villagers_traded, enchantments, times_slept,
        top_block_mined, top_mob_killed, top_item_crafted,
        top_item_used, top_death_cause,
        computed_at
      ) VALUES (
        ${row.id as number},
        ${row.minecraft_uuid as string},
        ${row.season as string},
        ${row.play_time_seconds as number},
        ${row.walk_distance_m as number},
        ${row.sprint_distance_m as number},
        ${row.swim_distance_m as number},
        ${row.fly_distance_m as number},
        ${row.boat_distance_m as number},
        ${row.elytra_distance_m as number},
        ${row.horse_distance_m as number},
        ${row.climb_distance_m as number},
        ${row.fall_distance_m as number},
        ${row.total_distance_m as number},
        ${row.mob_kills as number},
        ${row.player_kills as number},
        ${row.deaths as number},
        ${row.damage_dealt as number},
        ${row.damage_taken as number},
        ${row.total_blocks_mined as number},
        ${row.total_blocks_placed as number},
        ${row.total_items_crafted as number},
        ${row.total_items_broken as number},
        ${row.jumps as number},
        ${row.animals_bred as number},
        ${row.fish_caught as number},
        ${row.villagers_traded as number},
        ${row.enchantments as number},
        ${row.times_slept as number},
        ${(row.top_block_mined as string | null) ?? null},
        ${(row.top_mob_killed as string | null) ?? null},
        ${(row.top_item_crafted as string | null) ?? null},
        ${(row.top_item_used as string | null) ?? null},
        ${(row.top_death_cause as string | null) ?? null},
        ${row.computed_at as number}
      )
      ON CONFLICT (id) DO NOTHING
    `;
  }

  // Reset the serial sequence
  await pg`SELECT setval('player_season_stats_id_seq', (SELECT COALESCE(MAX(id), 0) FROM player_season_stats))`;

  console.log(`  player_season_stats: ${rows.length} rows migrated`);
  return rows.length;
}

async function main() {
  console.log("Starting Turso → PostgreSQL migration...\n");

  let totalRows = 0;

  // Order matters: users first (referenced by applications FK)
  totalRows += await migrateUsers();
  totalRows += await migrateApplications();
  totalRows += await migrateSeasons();
  totalRows += await migratePlayerSeasonStats();

  console.log(`\nMigration complete. ${totalRows} total rows migrated.`);

  // Verify counts
  console.log("\nVerification:");
  const pgUsers = await pg`SELECT COUNT(*) as count FROM users`;
  const pgApps = await pg`SELECT COUNT(*) as count FROM applications`;
  const pgSeasons = await pg`SELECT COUNT(*) as count FROM seasons`;
  const pgStats = await pg`SELECT COUNT(*) as count FROM player_season_stats`;

  console.log(`  PostgreSQL users: ${pgUsers[0].count}`);
  console.log(`  PostgreSQL applications: ${pgApps[0].count}`);
  console.log(`  PostgreSQL seasons: ${pgSeasons[0].count}`);
  console.log(`  PostgreSQL player_season_stats: ${pgStats[0].count}`);

  await pg.end();
  turso.close();
}

main().catch((err) => {
  console.error("Migration failed:", err);
  process.exit(1);
});
