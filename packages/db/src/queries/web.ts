import { eq, and, desc, sql, like, asc, count } from "drizzle-orm";
import { db } from "../client";
import {
  players,
  applications,
  seasons as seasonsTable,
  playerSeasonStats,
  playerAwardScores,
  playerCrownScores,
  AGGREGATE_SERVER_ID,
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
        JOIN players u ON u.minecraft_uuid = pss.minecraft_uuid
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
    .select({ minecraft_uuid: players.minecraft_uuid })
    .from(players)
    .where(eq(players.discord_id, discordId));
  if (rows.length === 0) return null;
  return rows[0].minecraft_uuid;
}

export async function getPlayerRole(
  minecraftUuid: string,
): Promise<string> {
  const rows = await db
    .select({ role: players.role })
    .from(players)
    .where(eq(players.minecraft_uuid, minecraftUuid));
  if (rows.length === 0) return "unverified";
  return rows[0].role;
}

export async function getMinecraftUsername(
  discordId: string,
): Promise<string | null> {
  const rows = await db
    .select({ minecraft_username: players.minecraft_username })
    .from(players)
    .where(eq(players.discord_id, discordId));
  if (rows.length === 0) return null;
  return rows[0].minecraft_username;
}

export async function searchUsers(
  query: string,
  limit = 10,
): Promise<{ minecraft_uuid: string; minecraft_username: string }[]> {
  const rows = await db
    .select({
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
    })
    .from(players)
    .where(like(players.minecraft_username, `%${query}%`))
    .orderBy(asc(players.minecraft_username))
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
  role: string;
} | null> {
  const isUuid = identifier.includes("-") || identifier.length === 32;
  const rows = await db
    .select({
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
      role: players.role,
    })
    .from(players)
    .where(
      isUuid
        ? eq(players.minecraft_uuid, identifier)
        : sql`LOWER(${players.minecraft_username}) = LOWER(${identifier})`,
    );
  if (rows.length === 0) return null;
  const row = rows[0];
  if (!row.minecraft_uuid || !row.minecraft_username) return null;
  return {
    minecraft_uuid: row.minecraft_uuid,
    minecraft_username: row.minecraft_username,
    role: row.role,
  };
}

export async function getJoinedSeason(
  minecraftUuid: string,
): Promise<string | null> {
  const rows = await db
    .select({ season: applications.season })
    .from(applications)
    .innerJoin(players, eq(players.discord_id, applications.discord_id))
    .where(
      and(
        eq(players.minecraft_uuid, minecraftUuid),
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
            u.role, u.last_login_at, u.created_at,
            (SELECT a.season FROM applications a
             WHERE a.discord_id = u.discord_id AND a.status = 'accepted'
             ORDER BY a.applied_at ASC LIMIT 1) as joined_season
     FROM players u ORDER BY u.last_login_at DESC NULLS LAST`,
  );
  return (rows as unknown as Array<Record<string, unknown>>).map((row) => ({
    discord_id: row.discord_id as string,
    discord_username: row.discord_username as string,
    minecraft_username: (row.minecraft_username as string | null) ?? null,
    minecraft_uuid: (row.minecraft_uuid as string | null) ?? null,
    role: row.role as string,
    last_login_at: (row.last_login_at as number | null) ?? null,
    created_at: row.created_at as number,
    joined_season: (row.joined_season as string | null) ?? null,
  }));
}

export async function getPlayerProfile(
  minecraftUuid: string,
): Promise<{
  discord_username: string | null;
} | null> {
  const rows = await db
    .select({
      discord_username: players.discord_username,
    })
    .from(players)
    .where(eq(players.minecraft_uuid, minecraftUuid));
  if (rows.length === 0) return null;
  return {
    discord_username: rows[0].discord_username,
  };
}

// ── Admin queries ───────────────────────────────────────────────

export async function getOverviewStats(): Promise<{
  playerCount: number;
  applicationsByStatus: Record<string, number>;
  currentSeason: Season | null;
  recentApplications: Application[];
}> {
  const [playerRows, statusRows, currentSeason, recentApps] = await Promise.all([
    db.select({ count: sql<number>`COUNT(*)` }).from(players),
    db.execute(
      sql`SELECT status, COUNT(*)::int as count FROM applications GROUP BY status`,
    ),
    getCurrentSeason(),
    db
      .select()
      .from(applications)
      .orderBy(desc(applications.applied_at))
      .limit(10),
  ]);

  const applicationsByStatus: Record<string, number> = {};
  for (const row of statusRows as unknown as Array<{ status: string; count: number }>) {
    applicationsByStatus[row.status] = row.count;
  }

  return {
    playerCount: Number(playerRows[0]?.count ?? 0),
    applicationsByStatus,
    currentSeason,
    recentApplications: recentApps.map((row) => ({
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
    })),
  };
}

export async function getAllApplications(filters?: {
  status?: string;
  season?: string;
  limit?: number;
  offset?: number;
}): Promise<Application[]> {
  const conditions = [];
  if (filters?.status) conditions.push(eq(applications.status, filters.status));
  if (filters?.season) conditions.push(eq(applications.season, filters.season));

  const query = db
    .select()
    .from(applications)
    .orderBy(desc(applications.applied_at))
    .limit(filters?.limit ?? 50)
    .offset(filters?.offset ?? 0);

  const rows = conditions.length > 0
    ? await query.where(and(...conditions))
    : await query;

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

export async function createSeason(data: {
  id: string;
  name: string;
  start_date?: string;
  end_date?: string;
}): Promise<void> {
  await db.insert(seasonsTable).values({
    id: data.id,
    name: data.name,
    start_date: data.start_date ?? null,
    end_date: data.end_date ?? null,
  });
}

export async function updateSeason(
  id: string,
  data: { name?: string; start_date?: string; end_date?: string },
): Promise<void> {
  await db
    .update(seasonsTable)
    .set(data)
    .where(eq(seasonsTable.id, id));
}

export async function setCurrentSeason(seasonId: string): Promise<void> {
  await db.update(seasonsTable).set({ is_current: false });
  await db
    .update(seasonsTable)
    .set({ is_current: true })
    .where(eq(seasonsTable.id, seasonId));
}

export async function setPlayerRole(
  discordId: string,
  role: "unverified" | "verified" | "moderator" | "admin",
): Promise<void> {
  await db
    .update(players)
    .set({ role: role, updated_at: Math.floor(Date.now() / 1000) })
    .where(eq(players.discord_id, discordId));
}

// ── Award queries ───────────────────────────────────────────────

export interface AwardLeaderboardEntry {
  rank: number;
  minecraft_uuid: string;
  minecraft_username: string | null;
  score: number;
  medal: number; // 0 none, 1 gold, 2 silver, 3 bronze
}

export interface AwardSummaryEntry {
  award_id: string;
  best_uuid: string | null;
  best_username: string | null;
  best_score: number;
}

export interface CrownLeaderboardEntry {
  rank: number;
  minecraft_uuid: string;
  minecraft_username: string | null;
  gold: number;
  silver: number;
  bronze: number;
  crown_score: number;
}

export interface PlayerAwardHolding {
  award_id: string;
  score: number;
  medal: number;
  rank: number;
}

export const AWARD_AGGREGATE_SERVER_ID = AGGREGATE_SERVER_ID;

/**
 * Per-award leaderboard, ordered by score desc. Serves /awards/[key].
 */
export async function getAwardLeaderboard(
  awardId: string,
  season: string,
  serverId: string = AGGREGATE_SERVER_ID,
  limit = 50,
): Promise<AwardLeaderboardEntry[]> {
  const rows = await db
    .select({
      uuid: playerAwardScores.minecraft_uuid,
      username: players.minecraft_username,
      score: playerAwardScores.score,
      medal: playerAwardScores.medal,
    })
    .from(playerAwardScores)
    .leftJoin(
      players,
      eq(players.minecraft_uuid, playerAwardScores.minecraft_uuid),
    )
    .where(
      and(
        eq(playerAwardScores.award_id, awardId),
        eq(playerAwardScores.season, season),
        eq(playerAwardScores.server_id, serverId),
      ),
    )
    .orderBy(desc(playerAwardScores.score))
    .limit(limit);

  return rows.map((row, i) => ({
    rank: i + 1,
    minecraft_uuid: row.uuid,
    minecraft_username: row.username,
    score: Number(row.score),
    medal: row.medal,
  }));
}

/**
 * One-row-per-award summary: the current #1 holder for each award.
 * Used by the /awards overview page.
 */
export async function getAwardsSummary(
  season: string,
  serverId: string = AGGREGATE_SERVER_ID,
): Promise<AwardSummaryEntry[]> {
  const rows = await db.execute(
    sql`
      SELECT DISTINCT ON (p.award_id)
        p.award_id,
        p.minecraft_uuid AS best_uuid,
        u.minecraft_username AS best_username,
        p.score AS best_score
      FROM player_award_scores p
      LEFT JOIN players u ON u.minecraft_uuid = p.minecraft_uuid
      WHERE p.season = ${season}
        AND p.server_id = ${serverId}
        AND p.score > 0
      ORDER BY p.award_id, p.score DESC
    `,
  );
  return (
    rows as unknown as Array<{
      award_id: string;
      best_uuid: string | null;
      best_username: string | null;
      best_score: number | string;
    }>
  ).map((row) => ({
    award_id: row.award_id,
    best_uuid: row.best_uuid,
    best_username: row.best_username,
    best_score: Number(row.best_score ?? 0),
  }));
}

/**
 * Hall of Fame ranking (crown score). Serves /leaderboard.
 */
export async function getCrownLeaderboard(
  season: string,
  serverId: string = AGGREGATE_SERVER_ID,
  limit = 100,
): Promise<CrownLeaderboardEntry[]> {
  const rows = await db
    .select({
      uuid: playerCrownScores.minecraft_uuid,
      username: players.minecraft_username,
      gold: playerCrownScores.gold,
      silver: playerCrownScores.silver,
      bronze: playerCrownScores.bronze,
      crown_score: playerCrownScores.crown_score,
    })
    .from(playerCrownScores)
    .leftJoin(
      players,
      eq(players.minecraft_uuid, playerCrownScores.minecraft_uuid),
    )
    .where(
      and(
        eq(playerCrownScores.season, season),
        eq(playerCrownScores.server_id, serverId),
        sql`${playerCrownScores.crown_score} > 0`,
      ),
    )
    .orderBy(
      desc(playerCrownScores.crown_score),
      desc(playerCrownScores.gold),
      desc(playerCrownScores.silver),
    )
    .limit(limit);

  return rows.map((row, i) => ({
    rank: i + 1,
    minecraft_uuid: row.uuid,
    minecraft_username: row.username,
    gold: row.gold,
    silver: row.silver,
    bronze: row.bronze,
    crown_score: row.crown_score,
  }));
}

/**
 * All medal-earning award entries held by one player (for their profile page).
 * Returns rows where the player is on the podium (medal > 0). Includes per-award rank.
 */
export async function getPlayerAwardHoldings(
  uuid: string,
  season: string,
  serverId: string = AGGREGATE_SERVER_ID,
): Promise<PlayerAwardHolding[]> {
  const rows = await db.execute(
    sql`
      SELECT award_id, score, medal, rank FROM (
        SELECT
          award_id,
          minecraft_uuid,
          score,
          medal,
          RANK() OVER (PARTITION BY award_id ORDER BY score DESC) AS rank
        FROM player_award_scores
        WHERE season = ${season}
          AND server_id = ${serverId}
          AND score > 0
      ) ranked
      WHERE minecraft_uuid = ${uuid} AND medal > 0
      ORDER BY medal ASC, score DESC
    `,
  );
  return (
    rows as unknown as Array<{
      award_id: string;
      score: number | string;
      medal: number;
      rank: number | string;
    }>
  ).map((row) => ({
    award_id: row.award_id,
    score: Number(row.score),
    medal: row.medal,
    rank: Number(row.rank),
  }));
}

/**
 * Single crown-score row for a player (for their profile page).
 */
export async function getPlayerCrownScore(
  uuid: string,
  season: string,
  serverId: string = AGGREGATE_SERVER_ID,
): Promise<CrownLeaderboardEntry | null> {
  const rows = await db.execute(
    sql`
      SELECT
        p.minecraft_uuid,
        u.minecraft_username,
        p.gold,
        p.silver,
        p.bronze,
        p.crown_score,
        (
          SELECT COUNT(*) + 1
          FROM player_crown_scores p2
          WHERE p2.season = p.season
            AND p2.server_id = p.server_id
            AND p2.crown_score > p.crown_score
        ) AS rank
      FROM player_crown_scores p
      LEFT JOIN players u ON u.minecraft_uuid = p.minecraft_uuid
      WHERE p.minecraft_uuid = ${uuid}
        AND p.season = ${season}
        AND p.server_id = ${serverId}
      LIMIT 1
    `,
  );
  const first = (
    rows as unknown as Array<{
      minecraft_uuid: string;
      minecraft_username: string | null;
      gold: number;
      silver: number;
      bronze: number;
      crown_score: number;
      rank: number | string;
    }>
  )[0];
  if (!first) return null;
  return {
    rank: Number(first.rank),
    minecraft_uuid: first.minecraft_uuid,
    minecraft_username: first.minecraft_username,
    gold: first.gold,
    silver: first.silver,
    bronze: first.bronze,
    crown_score: first.crown_score,
  };
}

/**
 * All award scores a player has (score > 0) for a season/server, with rank.
 * Used for the per-player detailed stats table on their profile page.
 */
export async function getPlayerAwardScores(
  uuid: string,
  season: string,
  serverId: string = AGGREGATE_SERVER_ID,
): Promise<Record<string, { rank: number; value: number }>> {
  const rows = await db.execute(
    sql`
      SELECT award_id, score, rank FROM (
        SELECT
          award_id,
          minecraft_uuid,
          score,
          RANK() OVER (PARTITION BY award_id ORDER BY score DESC) AS rank
        FROM player_award_scores
        WHERE season = ${season}
          AND server_id = ${serverId}
          AND score > 0
      ) ranked
      WHERE minecraft_uuid = ${uuid}
    `,
  );
  const out: Record<string, { rank: number; value: number }> = {};
  for (const row of rows as unknown as Array<{
    award_id: string;
    score: number | string;
    rank: number | string;
  }>) {
    out[row.award_id] = { rank: Number(row.rank), value: Number(row.score) };
  }
  return out;
}

/**
 * Distinct server_ids that have award data for a season, for the UI server toggle.
 * Excludes the aggregate sentinel — callers typically render it separately.
 */
export async function getAwardServers(season: string): Promise<string[]> {
  const rows = await db
    .selectDistinct({ server_id: playerAwardScores.server_id })
    .from(playerAwardScores)
    .where(
      and(
        eq(playerAwardScores.season, season),
        sql`${playerAwardScores.server_id} <> ${AGGREGATE_SERVER_ID}`,
      ),
    )
    .orderBy(asc(playerAwardScores.server_id));
  return rows.map((r) => r.server_id);
}
