import {
  eq,
  and,
  or,
  desc,
  sql,
  like,
  ilike,
  asc,
  count,
  inArray,
  isNull,
  gt,
  lt,
} from "drizzle-orm";
import { db } from "../client";
import {
  players,
  playerAlts,
  applications,
  seasons as seasonsTable,
  playerSeasonStats,
  playerAwardScores,
  awards,
  streamChannels,
  blockGradientShares,
  playerLoginStreaks,
  galleryPosts,
  galleryImages,
  galleryReactions,
  galleryTags,
  galleryPostTags,
} from "../schema";
import type {
  PlayerSeasonStats,
  Season,
  LeaderboardCategory,
  TopItem,
  AdminUser,
  Application,
} from "@crabcraft/shared/types";

export interface GalleryTag {
  id: string;
  filterKey: string;
  name: string;
  emojiName: string | null;
  emojiUrl: string | null;
}

/** One cross-channel filter option, keyed by a normalised Discord tag name. */
export interface GalleryFilterTag {
  key: string;
  name: string;
  emojiName: string | null;
  emojiUrl: string | null;
}

export interface GalleryPlayerFilterOption {
  username: string;
  nickname: string | null;
  avatarUrl: string;
}

// ── Block Gradient shares ─────────────────────────────────────

export async function createBlockGradientShare(
  id: string,
  version: number,
  state: Record<string, unknown>,
): Promise<void> {
  await db
    .insert(blockGradientShares)
    .values({ id, version, state })
    .onConflictDoNothing();
}

export async function getBlockGradientShare(
  id: string,
): Promise<{ version: number; state: Record<string, unknown> } | null> {
  const [share] = await db
    .select({
      version: blockGradientShares.version,
      state: blockGradientShares.state,
    })
    .from(blockGradientShares)
    .where(eq(blockGradientShares.id, id))
    .limit(1);
  return share ?? null;
}

export interface GalleryImage {
  id: string;
  url: string;
  alt: string | null;
  width: number | null;
  height: number | null;
}

export interface GalleryReaction {
  key: string;
  name: string;
  emojiUrl: string | null;
  count: number;
}

export interface GalleryPostAuthor {
  username: string;
  nickname: string | null;
  profileHref: string | null;
  avatarUrl: string;
}

export interface GalleryPost {
  id: string;
  title: string;
  content: string | null;
  season: number;
  postedAt: Date;
  updatedAt: Date;
  author: GalleryPostAuthor;
  tags: GalleryTag[];
  images: GalleryImage[];
  reactions: GalleryReaction[];
}

export interface GalleryPostLink {
  id: string;
  title: string;
}

interface GalleryPostRow {
  id: string;
  title: string;
  content: string | null;
  season: number;
  postedAt: number;
  contentUpdatedAt: number;
  authorDiscordUsername: string;
  authorDisplayName: string;
  minecraftUsername: string | null;
  minecraftNickname: string | null;
  minecraftUuid: string | null;
}

function galleryTagFromRow(row: {
  id: string;
  key: string;
  name: string;
  emojiId: string | null;
  emojiName: string | null;
}): GalleryTag {
  return {
    id: row.id,
    filterKey: row.key,
    name: row.name,
    // Discord supplies a name for custom emoji too, but the website only
    // renders emojiName as text for native Unicode emoji.
    emojiName: row.emojiId ? null : row.emojiName,
    emojiUrl: row.emojiId
      ? `https://cdn.discordapp.com/emojis/${row.emojiId}.webp?size=32&quality=lossless`
      : null,
  };
}

function galleryAuthorFromRow(row: GalleryPostRow): GalleryPostAuthor {
  const username =
    row.minecraftUsername ??
    row.authorDisplayName ??
    row.authorDiscordUsername;
  return {
    username,
    nickname: row.minecraftNickname,
    profileHref: row.minecraftUsername
      ? `/stats/${encodeURIComponent(row.minecraftUsername)}`
      : null,
    avatarUrl: row.minecraftUuid
      ? `https://mc-heads.net/avatar/${row.minecraftUuid}/64.png`
      : "/logo.png",
  };
}

async function hydrateGalleryPosts(
  rows: GalleryPostRow[],
): Promise<GalleryPost[]> {
  if (rows.length === 0) return [];
  const postIds = rows.map((row) => row.id);
  const [imageRows, tagRows, reactionRows] = await Promise.all([
    db
      .select({
        postId: galleryImages.post_id,
        id: galleryImages.discord_attachment_id,
        url: galleryImages.public_url,
        alt: galleryImages.alt,
        width: galleryImages.width,
        height: galleryImages.height,
      })
      .from(galleryImages)
      .where(inArray(galleryImages.post_id, postIds))
      .orderBy(asc(galleryImages.position)),
    db
      .select({
        postId: galleryPostTags.post_id,
        id: galleryTags.discord_tag_id,
        key: sql<string>`lower(btrim(${galleryTags.name}))`,
        name: galleryTags.name,
        emojiId: galleryTags.emoji_id,
        emojiName: galleryTags.emoji_name,
      })
      .from(galleryPostTags)
      .innerJoin(
        galleryTags,
        eq(galleryTags.discord_tag_id, galleryPostTags.tag_id),
      )
      .where(inArray(galleryPostTags.post_id, postIds))
      .orderBy(asc(galleryTags.position), asc(galleryTags.name)),
    db
      .select({
        postId: galleryReactions.post_id,
        key: galleryReactions.emoji_key,
        emojiId: galleryReactions.emoji_id,
        name: galleryReactions.emoji_name,
        animated: galleryReactions.animated,
        count: galleryReactions.count,
      })
      .from(galleryReactions)
      .where(inArray(galleryReactions.post_id, postIds))
      .orderBy(desc(galleryReactions.count), asc(galleryReactions.emoji_key)),
  ]);

  const imagesByPost = new Map<string, GalleryImage[]>();
  for (const image of imageRows) {
    const images = imagesByPost.get(image.postId) ?? [];
    images.push({
      id: image.id,
      url: image.url,
      alt: image.alt,
      width: image.width,
      height: image.height,
    });
    imagesByPost.set(image.postId, images);
  }

  const tagsByPost = new Map<string, GalleryTag[]>();
  for (const tag of tagRows) {
    const tags = tagsByPost.get(tag.postId) ?? [];
    tags.push(galleryTagFromRow(tag));
    tagsByPost.set(tag.postId, tags);
  }

  const reactionsByPost = new Map<string, GalleryReaction[]>();
  for (const reaction of reactionRows) {
    const reactions = reactionsByPost.get(reaction.postId) ?? [];
    reactions.push({
      key: reaction.key,
      name: reaction.name,
      emojiUrl: reaction.emojiId
        ? `https://cdn.discordapp.com/emojis/${reaction.emojiId}.${reaction.animated ? "gif" : "webp"}?size=48&quality=lossless`
        : null,
      count: reaction.count,
    });
    reactionsByPost.set(reaction.postId, reactions);
  }

  return rows.map((row) => ({
    id: row.id,
    title: row.title,
    content: row.content,
    season: row.season,
    postedAt: new Date(row.postedAt * 1000),
    updatedAt: new Date(row.contentUpdatedAt * 1000),
    author: galleryAuthorFromRow(row),
    tags: tagsByPost.get(row.id) ?? [],
    images: imagesByPost.get(row.id) ?? [],
    reactions: reactionsByPost.get(row.id) ?? [],
  }));
}

const galleryPostSelection = {
  id: galleryPosts.thread_id,
  title: galleryPosts.title,
  content: galleryPosts.content,
  season: galleryPosts.season_number,
  postedAt: galleryPosts.posted_at,
  contentUpdatedAt: galleryPosts.content_updated_at,
  authorDiscordUsername: galleryPosts.author_discord_username,
  authorDisplayName: galleryPosts.author_display_name,
  minecraftUsername: players.minecraft_username,
  minecraftNickname: players.nickname,
  minecraftUuid: players.minecraft_uuid,
};

/** Newest-first, published Gallery posts with optional season/tag filters. */
export async function getGalleryPosts(options: {
  season?: number;
  tag?: string;
  player?: string;
  limit: number;
  offset: number;
}): Promise<{ posts: GalleryPost[]; total: number }> {
  const limit = Math.min(100, Math.max(1, Math.floor(options.limit)));
  const offset = Math.max(0, Math.floor(options.offset));
  const conditions = [
    eq(galleryPosts.published, true),
    isNull(galleryPosts.deleted_at),
  ];
  if (options.season !== undefined) {
    conditions.push(eq(galleryPosts.season_number, options.season));
  }
  if (options.tag) {
    conditions.push(sql`EXISTS (
      SELECT 1 FROM gallery_post_tags AS selected_tag
      JOIN gallery_tags AS selected_tag_definition
        ON selected_tag_definition.discord_tag_id = selected_tag.tag_id
      WHERE selected_tag.post_id = ${galleryPosts.thread_id}
        AND lower(btrim(selected_tag_definition.name)) = lower(btrim(${options.tag}))
    )`);
  }
  if (options.player) {
    const playerPattern = `%${options.player.replace(/[\\%_]/g, "\\$&")}%`;
    conditions.push(
      or(
        ilike(players.minecraft_username, playerPattern),
        ilike(players.nickname, playerPattern),
        ilike(galleryPosts.author_display_name, playerPattern),
        ilike(galleryPosts.author_discord_username, playerPattern),
      )!,
    );
  }
  const where = and(...conditions);

  const [postRows, totalRows] = await Promise.all([
    db
      .select(galleryPostSelection)
      .from(galleryPosts)
      .leftJoin(players, eq(players.discord_id, galleryPosts.author_discord_id))
      .where(where)
      .orderBy(desc(galleryPosts.posted_at), desc(galleryPosts.thread_id))
      .limit(limit)
      .offset(offset),
    db
      .select({ value: count() })
      .from(galleryPosts)
      .leftJoin(players, eq(players.discord_id, galleryPosts.author_discord_id))
      .where(where),
  ]);

  return {
    posts: await hydrateGalleryPosts(postRows),
    total: Number(totalRows[0]?.value ?? 0),
  };
}

/** One representative image chosen from the visible Gallery posts. */
export async function getRandomGalleryImage(): Promise<{
  url: string;
  alt: string | null;
} | null> {
  const [image] = await db
    .select({
      url: galleryImages.public_url,
      alt: galleryImages.alt,
    })
    .from(galleryImages)
    .innerJoin(galleryPosts, eq(galleryPosts.thread_id, galleryImages.post_id))
    .where(
      and(
        eq(galleryImages.position, 0),
        eq(galleryPosts.published, true),
        isNull(galleryPosts.deleted_at),
      ),
    )
    .orderBy(sql`random()`)
    .limit(1);
  return image ?? null;
}

/** Seasons come from all published posts; tags come from the latest season. */
export async function getGalleryFilterOptions(): Promise<{
  seasons: number[];
  tags: GalleryFilterTag[];
  players: GalleryPlayerFilterOption[];
}> {
  const visiblePostConditions = and(
    eq(galleryPosts.published, true),
    isNull(galleryPosts.deleted_at),
  );
  const [seasonRows, playerRows] = await Promise.all([
    db
      .selectDistinct({ season: galleryPosts.season_number })
      .from(galleryPosts)
      .where(visiblePostConditions)
      .orderBy(asc(galleryPosts.season_number)),
    db
      .selectDistinctOn([galleryPosts.author_discord_id], {
        discordUsername: galleryPosts.author_discord_username,
        displayName: galleryPosts.author_display_name,
        minecraftUsername: players.minecraft_username,
        minecraftNickname: players.nickname,
        minecraftUuid: players.minecraft_uuid,
      })
      .from(galleryPosts)
      .leftJoin(players, eq(players.discord_id, galleryPosts.author_discord_id))
      .where(visiblePostConditions)
      .orderBy(
        galleryPosts.author_discord_id,
        desc(galleryPosts.posted_at),
      ),
  ]);
  const playerOptions = playerRows
    .map((row) => ({
      username:
        row.minecraftUsername ?? row.displayName ?? row.discordUsername,
      nickname: row.minecraftNickname,
      avatarUrl: row.minecraftUuid
        ? `https://mc-heads.net/avatar/${row.minecraftUuid}/64.png`
        : "/logo.png",
    }))
    .sort((left, right) =>
      (left.nickname?.trim() || left.username).localeCompare(
        right.nickname?.trim() || right.username,
        "en-GB",
        {
        sensitivity: "base",
        },
      ),
    );
  const latestSeason = seasonRows.at(-1)?.season;
  if (latestSeason === undefined) {
    return { seasons: [], tags: [], players: [] };
  }

  const tagRows = await db
    .select({
      id: galleryTags.discord_tag_id,
      key: sql<string>`lower(btrim(${galleryTags.name}))`,
      name: galleryTags.name,
      emojiId: galleryTags.emoji_id,
      emojiName: galleryTags.emoji_name,
    })
    .from(galleryTags)
    .where(
      and(
        eq(galleryTags.available, true),
        sql`EXISTS (
          SELECT 1
          FROM gallery_posts AS latest_season_post
          WHERE latest_season_post.channel_id = ${galleryTags.channel_id}
            AND latest_season_post.season_number = ${latestSeason}
            AND latest_season_post.published = TRUE
            AND latest_season_post.deleted_at IS NULL
        )`,
      ),
    )
    .orderBy(
      asc(galleryTags.position),
      asc(galleryTags.name),
      desc(galleryTags.discord_tag_id),
    );

  const logicalTags = new Map<string, GalleryFilterTag>();
  for (const row of tagRows) {
    const key = row.key;
    if (!key || logicalTags.has(key)) continue;
    const tag = galleryTagFromRow(row);
    logicalTags.set(key, {
      key,
      name: row.name.trim(),
      emojiName: tag.emojiName,
      emojiUrl: tag.emojiUrl,
    });
  }

  return {
    seasons: seasonRows.map((row) => row.season),
    players: playerOptions,
    tags: [...logicalTags.values()].sort((a, b) =>
      a.name.localeCompare(b.name, "en-GB", { sensitivity: "base" }),
    ),
  };
}

export async function getGalleryPost(id: string): Promise<GalleryPost | null> {
  const rows = await db
    .select(galleryPostSelection)
    .from(galleryPosts)
    .leftJoin(players, eq(players.discord_id, galleryPosts.author_discord_id))
    .where(
      and(
        eq(galleryPosts.thread_id, id),
        eq(galleryPosts.published, true),
        isNull(galleryPosts.deleted_at),
      ),
    )
    .limit(1);
  const posts = await hydrateGalleryPosts(rows);
  return posts[0] ?? null;
}

export async function getAdjacentGalleryPosts(id: string): Promise<{
  newer: GalleryPostLink | null;
  older: GalleryPostLink | null;
}> {
  const [current] = await db
    .select({ postedAt: galleryPosts.posted_at })
    .from(galleryPosts)
    .where(
      and(
        eq(galleryPosts.thread_id, id),
        eq(galleryPosts.published, true),
        isNull(galleryPosts.deleted_at),
      ),
    )
    .limit(1);
  if (!current) return { newer: null, older: null };

  const visible = [
    eq(galleryPosts.published, true),
    isNull(galleryPosts.deleted_at),
  ];
  const [newerRows, olderRows] = await Promise.all([
    db
      .select({ id: galleryPosts.thread_id, title: galleryPosts.title })
      .from(galleryPosts)
      .where(
        and(
          ...visible,
          or(
            gt(galleryPosts.posted_at, current.postedAt),
            and(
              eq(galleryPosts.posted_at, current.postedAt),
              gt(galleryPosts.thread_id, id),
            ),
          ),
        ),
      )
      .orderBy(asc(galleryPosts.posted_at), asc(galleryPosts.thread_id))
      .limit(1),
    db
      .select({ id: galleryPosts.thread_id, title: galleryPosts.title })
      .from(galleryPosts)
      .where(
        and(
          ...visible,
          or(
            lt(galleryPosts.posted_at, current.postedAt),
            and(
              eq(galleryPosts.posted_at, current.postedAt),
              lt(galleryPosts.thread_id, id),
            ),
          ),
        ),
      )
      .orderBy(desc(galleryPosts.posted_at), desc(galleryPosts.thread_id))
      .limit(1),
  ]);

  return {
    newer: newerRows[0] ?? null,
    older: olderRows[0] ?? null,
  };
}

/** Bounded until Gallery sitemaps are sharded. */
export async function getGallerySitemapEntries(): Promise<
  Array<{ id: string; updatedAt: Date }>
> {
  const rows = await db
    .select({
      id: galleryPosts.thread_id,
      updatedAt: galleryPosts.content_updated_at,
    })
    .from(galleryPosts)
    .where(
      and(
        eq(galleryPosts.published, true),
        isNull(galleryPosts.deleted_at),
      ),
    )
    .orderBy(
      desc(galleryPosts.content_updated_at),
      desc(galleryPosts.thread_id),
    )
    .limit(39_000);
  return rows.map((row) => ({
    id: row.id,
    updatedAt: new Date(Number(row.updatedAt) * 1000),
  }));
}

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

export async function getHomepageStats(): Promise<{
  seasonCount: number;
  acceptedCurrentSeasonPlayerCount: number;
}> {
  const [seasonRows, playerRows] = await Promise.all([
    db.select({ count: count() }).from(seasonsTable),
    db
      .select({ count: count(applications.discord_id) })
      .from(applications)
      .innerJoin(
        seasonsTable,
        and(
          eq(applications.season, seasonsTable.id),
          eq(seasonsTable.is_current, true),
        ),
      )
      .where(eq(applications.status, "accepted")),
  ]);

  return {
    seasonCount: seasonRows[0]?.count ?? 0,
    acceptedCurrentSeasonPlayerCount: playerRows[0]?.count ?? 0,
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

export async function getPlayerCurrentStreak(
  minecraftUuid: string,
): Promise<number> {
  const rows = await db
    .select({
      current_streak: playerLoginStreaks.current_streak,
      last_login_at: playerLoginStreaks.last_login_at,
    })
    .from(playerLoginStreaks)
    .where(eq(playerLoginStreaks.minecraft_uuid, minecraftUuid))
    .limit(1);

  if (rows.length === 0) return 0;

  const resetHourUtc = 6;
  const daySeconds = 86_400;
  const lastLoginAt = rows[0].last_login_at;
  const lastDay = Math.floor((lastLoginAt - resetHourUtc * 3600) / daySeconds);
  const expiresAt = (lastDay + 3) * daySeconds + resetHourUtc * 3600;
  const now = Math.floor(Date.now() / 1000);

  return now < expiresAt ? rows[0].current_streak : 0;
}

export async function searchUsers(
  query: string,
  limit = 10,
): Promise<
  {
    minecraft_uuid: string;
    minecraft_username: string;
    nickname: string | null;
  }[]
> {
  const pattern = `%${query.replace(/[%_\\]/g, (c) => '\\' + c)}%`;
  const rows = await db
    .select({
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
      nickname: players.nickname,
    })
    .from(players)
    .where(
      or(
        ilike(players.minecraft_username, pattern),
        ilike(players.nickname, pattern),
      ),
    )
    .orderBy(asc(players.minecraft_username))
    .limit(limit);
  return rows.filter(
    (
      r,
    ): r is {
      minecraft_uuid: string;
      minecraft_username: string;
      nickname: string | null;
    } =>
      r.minecraft_uuid !== null && r.minecraft_username !== null,
  );
}

export async function getUserByIdentifier(
  identifier: string,
): Promise<{
  minecraft_uuid: string;
  minecraft_username: string;
  role: string;
  nickname: string | null;
  nickname_raw: string | null;
} | null> {
  const isUuid = identifier.includes("-") || identifier.length === 32;
  const rows = await db
    .select({
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
      role: players.role,
      nickname: players.nickname,
      nickname_raw: players.nickname_raw,
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
    nickname: row.nickname,
    nickname_raw: row.nickname_raw,
  };
}

/**
 * Resolve an alt account (by UUID or username) to its owner's primary
 * Minecraft account. Alts live in player_alts, linked to the owner via
 * discord_id; the stats page uses this to redirect /stats/<alt> to the
 * owner's profile.
 */
export async function getAltOwner(
  identifier: string,
): Promise<{
  owner_uuid: string;
  owner_username: string;
} | null> {
  const isUuid = identifier.includes("-") || identifier.length === 32;
  const rows = await db
    .select({
      owner_uuid: players.minecraft_uuid,
      owner_username: players.minecraft_username,
    })
    .from(playerAlts)
    .innerJoin(players, eq(players.discord_id, playerAlts.discord_id))
    .where(
      isUuid
        ? eq(playerAlts.minecraft_uuid, identifier)
        : sql`LOWER(${playerAlts.minecraft_username}) = LOWER(${identifier})`,
    )
    .limit(1);
  const row = rows[0];
  if (!row?.owner_uuid || !row.owner_username) return null;
  return {
    owner_uuid: row.owner_uuid,
    owner_username: row.owner_username,
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
    age_met: row.age_met,
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
  channels: Array<{ platform: string; channel_id: string; display_name: string | null }>;
} | null> {
  const rows = await db
    .select({
      discord_id: players.discord_id,
      discord_username: players.discord_username,
    })
    .from(players)
    .where(eq(players.minecraft_uuid, minecraftUuid));
  if (rows.length === 0) return null;

  const channels = await db
    .select({
      platform: streamChannels.platform,
      channel_id: streamChannels.channel_id,
      display_name: streamChannels.display_name,
    })
    .from(streamChannels)
    .where(eq(streamChannels.discord_user_id, rows[0].discord_id));

  return {
    discord_username: rows[0].discord_username,
    channels,
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
      age_met: row.age_met,
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
    age_met: row.age_met,
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

// ── Award definition queries ────────────────────────────────────

export interface AwardDefinition {
  id: string;
  title: string;
  description: string;
  unit: string;
  bucket: string;
  icon: string;
  enabled: boolean;
  sort_order: number;
}

/**
 * All enabled awards, ordered for stable UI display. Small table
 * (~200 rows) so no pagination needed.
 */
export async function getAwardDefinitions(): Promise<AwardDefinition[]> {
  const rows = await db
    .select({
      id: awards.id,
      title: awards.title,
      description: awards.description,
      unit: awards.unit,
      bucket: awards.bucket,
      icon: awards.icon,
      enabled: awards.enabled,
      sort_order: awards.sort_order,
    })
    .from(awards)
    .where(eq(awards.enabled, true))
    .orderBy(asc(awards.bucket), asc(awards.sort_order), asc(awards.title));
  return rows;
}

/**
 * Single award lookup. Returns null if the award doesn't exist or is
 * disabled — used by /awards/[key] and the API route to 404 cleanly.
 */
export async function getAwardDefinition(
  id: string,
): Promise<AwardDefinition | null> {
  const rows = await db
    .select({
      id: awards.id,
      title: awards.title,
      description: awards.description,
      unit: awards.unit,
      bucket: awards.bucket,
      icon: awards.icon,
      enabled: awards.enabled,
      sort_order: awards.sort_order,
    })
    .from(awards)
    .where(and(eq(awards.id, id), eq(awards.enabled, true)))
    .limit(1);
  return rows[0] ?? null;
}

// ── Award score queries ─────────────────────────────────────────

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

/**
 * Per-award leaderboard, ordered by score desc. Serves /awards/[key].
 */
export async function getAwardLeaderboard(
  awardId: string,
  season: string,
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
    .innerJoin(
      players,
      eq(players.minecraft_uuid, playerAwardScores.minecraft_uuid),
    )
    .where(
      and(
        eq(playerAwardScores.award_id, awardId),
        eq(playerAwardScores.season, season),
        eq(players.is_discord_member, true),
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
        AND p.score > 0
        AND EXISTS (
          SELECT 1 FROM players eligible_player
          WHERE eligible_player.minecraft_uuid = p.minecraft_uuid
            AND eligible_player.is_discord_member = true
        )
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
 *
 * Aggregates medals from player_award_scores at query time. Crown
 * score weighting: gold * 5 + silver * 3 + bronze. At our scale the
 * scan is sub-millisecond; keeping this as a live query avoids the
 * write-amplification + sync problems of a rollup table.
 */
export async function getCrownLeaderboard(
  season: string,
  limit = 100,
): Promise<CrownLeaderboardEntry[]> {
  const rows = await db.execute(
    sql`
      SELECT
        c.minecraft_uuid,
        u.minecraft_username,
        c.gold,
        c.silver,
        c.bronze,
        c.crown_score
      FROM (
        SELECT
          minecraft_uuid,
          COUNT(*) FILTER (WHERE medal = 1)::int AS gold,
          COUNT(*) FILTER (WHERE medal = 2)::int AS silver,
          COUNT(*) FILTER (WHERE medal = 3)::int AS bronze,
          (COUNT(*) FILTER (WHERE medal = 1) * 5
           + COUNT(*) FILTER (WHERE medal = 2) * 3
           + COUNT(*) FILTER (WHERE medal = 3))::int AS crown_score
        FROM player_award_scores
        WHERE season = ${season}
          AND EXISTS (
            SELECT 1 FROM players eligible_player
            WHERE eligible_player.minecraft_uuid = player_award_scores.minecraft_uuid
              AND eligible_player.is_discord_member = true
          )
        GROUP BY minecraft_uuid
      ) c
      LEFT JOIN players u ON u.minecraft_uuid = c.minecraft_uuid
      WHERE c.crown_score > 0
      ORDER BY c.crown_score DESC, c.gold DESC, c.silver DESC
      LIMIT ${limit}
    `,
  );
  return (
    rows as unknown as Array<{
      minecraft_uuid: string;
      minecraft_username: string | null;
      gold: number;
      silver: number;
      bronze: number;
      crown_score: number;
    }>
  ).map((row, i) => ({
    rank: i + 1,
    minecraft_uuid: row.minecraft_uuid,
    minecraft_username: row.minecraft_username,
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
          AND score > 0
          AND EXISTS (
            SELECT 1 FROM players eligible_player
            WHERE eligible_player.minecraft_uuid = player_award_scores.minecraft_uuid
              AND eligible_player.is_discord_member = true
          )
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
 *
 * Computes the player's medals and their rank within the
 * season slice from player_award_scores in one pass.
 * Returns null when the player has no medal-earning awards.
 */
export async function getPlayerCrownScore(
  uuid: string,
  season: string,
): Promise<CrownLeaderboardEntry | null> {
  const rows = await db.execute(
    sql`
      WITH crown AS (
        SELECT
          minecraft_uuid,
          COUNT(*) FILTER (WHERE medal = 1)::int AS gold,
          COUNT(*) FILTER (WHERE medal = 2)::int AS silver,
          COUNT(*) FILTER (WHERE medal = 3)::int AS bronze,
          (COUNT(*) FILTER (WHERE medal = 1) * 5
           + COUNT(*) FILTER (WHERE medal = 2) * 3
           + COUNT(*) FILTER (WHERE medal = 3))::int AS crown_score
        FROM player_award_scores
        WHERE season = ${season}
          AND EXISTS (
            SELECT 1 FROM players eligible_player
            WHERE eligible_player.minecraft_uuid = player_award_scores.minecraft_uuid
              AND eligible_player.is_discord_member = true
          )
        GROUP BY minecraft_uuid
      ),
      ranked AS (
        SELECT
          minecraft_uuid, gold, silver, bronze, crown_score,
          RANK() OVER (
            ORDER BY crown_score DESC, gold DESC, silver DESC
          ) AS rank
        FROM crown
        WHERE crown_score > 0
      )
      SELECT r.*, u.minecraft_username
      FROM ranked r
      LEFT JOIN players u ON u.minecraft_uuid = r.minecraft_uuid
      WHERE r.minecraft_uuid = ${uuid}
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
          AND score > 0
          AND EXISTS (
            SELECT 1 FROM players eligible_player
            WHERE eligible_player.minecraft_uuid = player_award_scores.minecraft_uuid
              AND eligible_player.is_discord_member = true
          )
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

// ── Settings page queries ─────────────────────────────────────

export async function getPlayerJoinInfo(discordId: string): Promise<{ created_at: number; join_rank: number } | null> {
  const rows = await db.execute(sql`
    SELECT created_at, (
      SELECT COUNT(*) + 1 FROM players p2 WHERE p2.created_at < players.created_at
    )::integer AS join_rank
    FROM players
    WHERE discord_id = ${discordId}
    LIMIT 1
  `);
  const row = (rows as any)[0];
  if (!row) return null;
  return { created_at: row.created_at, join_rank: row.join_rank };
}

export async function isUuidPrimaryAccount(minecraftUuid: string): Promise<boolean> {
  const rows = await db
    .select({ discord_id: players.discord_id })
    .from(players)
    .where(eq(players.minecraft_uuid, minecraftUuid))
    .limit(1);
  return rows.length > 0;
}

export type Platform = "youtube" | "twitch" | "tiktok";

export async function addStreamChannelForUser(
  platform: Platform,
  channelId: string,
  discordUserId: string,
  displayName?: string,
): Promise<boolean> {
  const inserted = await db
    .insert(streamChannels)
    .values({
      platform,
      channel_id: channelId,
      discord_user_id: discordUserId,
      display_name: displayName ?? null,
    })
    .onConflictDoNothing()
    .returning({ id: streamChannels.id });
  return inserted.length > 0;
}

export async function removeStreamChannelForUser(
  platform: string,
  channelId: string,
  discordUserId: string,
): Promise<boolean> {
  const result = await db
    .delete(streamChannels)
    .where(
      and(
        eq(streamChannels.platform, platform),
        eq(streamChannels.channel_id, channelId),
        eq(streamChannels.discord_user_id, discordUserId),
      ),
    );
  return (result as any).rowCount > 0;
}

export async function getStreamChannelsForUser(
  discordUserId: string,
): Promise<{ id: number; platform: string; channel_id: string; display_name: string | null }[]> {
  return db
    .select({
      id: streamChannels.id,
      platform: streamChannels.platform,
      channel_id: streamChannels.channel_id,
      display_name: streamChannels.display_name,
    })
    .from(streamChannels)
    .where(eq(streamChannels.discord_user_id, discordUserId));
}
