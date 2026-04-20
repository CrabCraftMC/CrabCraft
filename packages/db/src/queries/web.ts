import { eq, and, desc, sql, like, asc, count } from "drizzle-orm";
import { db } from "../client";
import {
  users,
  applications,
  seasons as seasonsTable,
  playerSeasonStats,
} from "../schema";
import type {
  PlayerSeasonStats,
  Season,
  LeaderboardEntry,
  LeaderboardCategory,
  TopItem,
  AdminUser,
  Application,
} from "@crabcraft/shared/types";

const CATEGORY_MAP: Record<string, keyof typeof playerSeasonStats.$inferSelect> = {
  play_time_seconds: "play_time_seconds",
  total_distance_m: "total_distance_m",
  total_blocks_mined: "total_blocks_mined",
  mob_kills: "mob_kills",
  deaths: "deaths",
  total_items_crafted: "total_items_crafted",
  fish_caught: "fish_caught",
  animals_bred: "animals_bred",
  jumps: "jumps",
  damage_dealt: "damage_dealt",
};

function safeCategory(category: string): string {
  const col = CATEGORY_MAP[category];
  if (!col) throw new Error("Invalid category");
  return col;
}

function safeParseTopItem(json: unknown): TopItem | null {
  if (!json || typeof json !== "string") return null;
  try {
    return JSON.parse(json);
  } catch {
    return null;
  }
}

export interface SeasonWithPlaytime extends Season {
  play_time_seconds: number;
}

export async function getSeasons(): Promise<Season[]> {
  const rows = await db
    .select()
    .from(seasonsTable)
    .orderBy(asc(seasonsTable.id));
  return rows.map((row) => ({
    id: row.id,
    name: row.name,
    start_date: row.start_date,
    end_date: row.end_date,
    is_current: row.is_current,
  }));
}

export async function getPlayerSeasons(
  uuid: string,
): Promise<SeasonWithPlaytime[]> {
  const rows = await db
    .select({
      id: seasonsTable.id,
      name: seasonsTable.name,
      start_date: seasonsTable.start_date,
      end_date: seasonsTable.end_date,
      is_current: seasonsTable.is_current,
      play_time_seconds: playerSeasonStats.play_time_seconds,
    })
    .from(seasonsTable)
    .innerJoin(
      playerSeasonStats,
      eq(playerSeasonStats.season, seasonsTable.id),
    )
    .where(eq(playerSeasonStats.minecraft_uuid, uuid))
    .orderBy(asc(seasonsTable.id));
  return rows.map((row) => ({
    id: row.id,
    name: row.name,
    start_date: row.start_date,
    end_date: row.end_date,
    is_current: row.is_current,
    play_time_seconds: row.play_time_seconds,
  }));
}

export async function getCurrentSeason(): Promise<Season | null> {
  const rows = await db
    .select()
    .from(seasonsTable)
    .where(eq(seasonsTable.is_current, true))
    .limit(1);
  if (rows.length === 0) return null;
  const row = rows[0];
  return {
    id: row.id,
    name: row.name,
    start_date: row.start_date,
    end_date: row.end_date,
    is_current: true,
  };
}

export async function getPlayerStats(
  uuid: string,
  season: string,
): Promise<PlayerSeasonStats | null> {
  const rows = await db
    .select()
    .from(playerSeasonStats)
    .where(
      and(
        eq(playerSeasonStats.minecraft_uuid, uuid),
        eq(playerSeasonStats.season, season),
      ),
    );
  if (rows.length === 0) return null;
  const row = rows[0];
  return {
    ...row,
    top_block_mined: safeParseTopItem(row.top_block_mined),
    top_mob_killed: safeParseTopItem(row.top_mob_killed),
    top_item_crafted: safeParseTopItem(row.top_item_crafted),
    top_item_used: safeParseTopItem(row.top_item_used),
    top_death_cause: safeParseTopItem(row.top_death_cause),
  } as unknown as PlayerSeasonStats;
}

export async function getLeaderboard(
  season: string,
  category: LeaderboardCategory,
  limit = 50,
): Promise<LeaderboardEntry[]> {
  const col = safeCategory(category);
  // Use raw SQL for dynamic column ordering since Drizzle doesn't support dynamic column refs easily
  const rows = await db.execute(
    sql`SELECT pss.minecraft_uuid, u.minecraft_username, pss.${sql.identifier(col)} as value
        FROM player_season_stats pss
        JOIN users u ON u.minecraft_uuid = pss.minecraft_uuid
        WHERE pss.season = ${season}
        ORDER BY pss.${sql.identifier(col)} DESC
        LIMIT ${limit}`,
  );
  return (rows as unknown as Array<{ minecraft_uuid: string; minecraft_username: string; value: number }>).map(
    (row, i) => ({
      minecraft_uuid: row.minecraft_uuid,
      minecraft_username: row.minecraft_username,
      value: Number(row.value),
      rank: i + 1,
    }),
  );
}

export async function getPlayerRank(
  uuid: string,
  season: string,
  category: LeaderboardCategory,
): Promise<number> {
  const col = safeCategory(category);
  const rows = await db.execute(
    sql`SELECT COUNT(*) + 1 as rank FROM player_season_stats
        WHERE season = ${season} AND ${sql.identifier(col)} > (
          SELECT COALESCE(${sql.identifier(col)}, 0) FROM player_season_stats
          WHERE minecraft_uuid = ${uuid} AND season = ${season}
        )`,
  );
  return Number((rows as unknown as Array<{ rank: number }>)[0]?.rank ?? 1);
}

export async function getServerAverages(
  season: string,
): Promise<Record<string, number>> {
  const rows = await db.execute(
    sql`SELECT
          AVG(play_time_seconds) as avg_play_time,
          AVG(total_distance_m) as avg_distance,
          AVG(total_blocks_mined) as avg_blocks,
          AVG(mob_kills) as avg_kills,
          AVG(deaths) as avg_deaths,
          COUNT(*) as player_count
        FROM player_season_stats WHERE season = ${season}`,
  );
  const row = (rows as unknown as Array<Record<string, unknown>>)[0];
  return {
    avg_play_time: Number(row?.avg_play_time ?? 0),
    avg_distance: Number(row?.avg_distance ?? 0),
    avg_blocks: Number(row?.avg_blocks ?? 0),
    avg_kills: Number(row?.avg_kills ?? 0),
    avg_deaths: Number(row?.avg_deaths ?? 0),
    player_count: Number(row?.player_count ?? 0),
  };
}

export async function getMinecraftUuid(
  discordId: string,
): Promise<string | null> {
  const rows = await db
    .select({ minecraft_uuid: users.minecraft_uuid })
    .from(users)
    .where(eq(users.discord_id, discordId));
  if (rows.length === 0) return null;
  return rows[0].minecraft_uuid;
}

export async function isPlayerAdmin(
  minecraftUuid: string,
): Promise<boolean> {
  const rows = await db
    .select({ is_admin: users.is_admin })
    .from(users)
    .where(eq(users.minecraft_uuid, minecraftUuid));
  if (rows.length === 0) return false;
  return rows[0].is_admin;
}

export async function getMinecraftUsername(
  discordId: string,
): Promise<string | null> {
  const rows = await db
    .select({ minecraft_username: users.minecraft_username })
    .from(users)
    .where(eq(users.discord_id, discordId));
  if (rows.length === 0) return null;
  return rows[0].minecraft_username;
}

export async function searchUsers(
  query: string,
  limit = 10,
): Promise<{ minecraft_uuid: string; minecraft_username: string }[]> {
  const rows = await db
    .select({
      minecraft_uuid: users.minecraft_uuid,
      minecraft_username: users.minecraft_username,
    })
    .from(users)
    .where(like(users.minecraft_username, `%${query}%`))
    .orderBy(asc(users.minecraft_username))
    .limit(limit);
  return rows.filter(
    (r): r is { minecraft_uuid: string; minecraft_username: string } =>
      r.minecraft_uuid !== null && r.minecraft_username !== null,
  );
}

export async function getUserByIdentifier(
  identifier: string,
): Promise<{
  minecraft_uuid: string;
  minecraft_username: string;
  is_admin: boolean;
} | null> {
  const isUuid = identifier.includes("-") || identifier.length === 32;
  const rows = await db
    .select({
      minecraft_uuid: users.minecraft_uuid,
      minecraft_username: users.minecraft_username,
      is_admin: users.is_admin,
    })
    .from(users)
    .where(
      isUuid
        ? eq(users.minecraft_uuid, identifier)
        : sql`LOWER(${users.minecraft_username}) = LOWER(${identifier})`,
    );
  if (rows.length === 0) return null;
  const row = rows[0];
  if (!row.minecraft_uuid || !row.minecraft_username) return null;
  return {
    minecraft_uuid: row.minecraft_uuid,
    minecraft_username: row.minecraft_username,
    is_admin: row.is_admin,
  };
}

export async function getJoinedSeason(
  minecraftUuid: string,
): Promise<string | null> {
  const rows = await db
    .select({ season: applications.season })
    .from(applications)
    .innerJoin(users, eq(users.discord_id, applications.discord_id))
    .where(
      and(
        eq(users.minecraft_uuid, minecraftUuid),
        eq(applications.status, "accepted"),
      ),
    )
    .orderBy(asc(applications.applied_at))
    .limit(1);
  if (rows.length === 0) return null;
  return rows[0].season;
}

export async function getUserApplications(
  discordId: string,
): Promise<Application[]> {
  const rows = await db
    .select()
    .from(applications)
    .where(eq(applications.discord_id, discordId))
    .orderBy(desc(applications.applied_at));
  return rows.map((row) => ({
    discord_id: row.discord_id,
    discord_username: row.discord_username,
    minecraft_username: row.minecraft_username,
    minecraft_uuid: row.minecraft_uuid ?? "",
    over_15: row.over_15,
    voice_chat: row.voice_chat,
    policy_agreed: row.policy_agreed,
    status: row.status as "pending" | "accepted" | "denied",
    join_reason: row.join_reason ?? "",
    favourite_wood: row.favourite_wood ?? "",
    denial_reason: row.denial_reason ?? null,
    season: row.season ?? "",
    applied_at: row.applied_at,
    resolved_at: row.resolved_at ?? null,
    resolved_by_discord_id: row.resolved_by_discord_id ?? null,
  }));
}

export async function getAdminUsers(): Promise<AdminUser[]> {
  const rows = await db.execute(
    sql`SELECT u.discord_id, u.discord_username, u.minecraft_username, u.minecraft_uuid,
            u.active, u.last_login_at, u.created_at,
            (SELECT a.season FROM applications a
             WHERE a.discord_id = u.discord_id AND a.status = 'accepted'
             ORDER BY a.applied_at ASC LIMIT 1) as joined_season
     FROM users u ORDER BY u.last_login_at DESC NULLS LAST`,
  );
  return (rows as unknown as Array<Record<string, unknown>>).map((row) => ({
    discord_id: row.discord_id as string,
    discord_username: row.discord_username as string,
    minecraft_username: (row.minecraft_username as string | null) ?? null,
    minecraft_uuid: (row.minecraft_uuid as string | null) ?? null,
    active: Boolean(row.active),
    last_login_at: (row.last_login_at as number | null) ?? null,
    created_at: row.created_at as number,
    joined_season: (row.joined_season as string | null) ?? null,
  }));
}

export async function getPlayerProfile(
  minecraftUuid: string,
): Promise<{
  discord_username: string | null;
  active: boolean;
} | null> {
  const rows = await db
    .select({
      discord_username: users.discord_username,
      active: users.active,
    })
    .from(users)
    .where(eq(users.minecraft_uuid, minecraftUuid));
  if (rows.length === 0) return null;
  return {
    discord_username: rows[0].discord_username,
    active: rows[0].active,
  };
}
