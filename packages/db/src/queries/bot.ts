import { eq, and, asc, desc, sql, lte, ne, ilike, isNull, isNotNull } from "drizzle-orm";
import { db } from "../client";
import {
  players,
  seasons,
  applications,
  streamChannels,
  playerAlts,
  starboardPosts,
  countingState,
  tickets,
  applicationChannels,
  type TicketCategory,
} from "../schema";

export type { TicketCategory, TicketStatus } from "../schema";

export interface UpsertUserData {
  discordId: string;
  discordUsername: string;
  minecraftUsername?: string;
  minecraftUuid?: string;
}

export interface CreateApplicationData {
  discordId: string;
  discordUsername: string;
  minecraftUsername: string;
  minecraftUuid: string | null;
  ageMet: boolean;
  voiceChat: boolean;
  joinReason?: string;
  favouriteWood?: string;
  season?: string | null;
}

export async function upsertUser(data: UpsertUserData): Promise<void> {
  await db.transaction(async (tx) => {
    // players.minecraft_uuid is unique; if some other discord_id currently
    // owns this UUID (e.g. a prior denied/cancelled application), detach it
    // so the upsert's ON CONFLICT (discord_id) clause can resolve cleanly.
    if (data.minecraftUuid) {
      await tx
        .update(players)
        .set({ minecraft_uuid: null, minecraft_username: null })
        .where(
          and(
            eq(players.minecraft_uuid, data.minecraftUuid),
            ne(players.discord_id, data.discordId),
          ),
        );
    }

    await tx
      .insert(players)
      .values({
        discord_id: data.discordId,
        discord_username: data.discordUsername,
        minecraft_username: data.minecraftUsername ?? null,
        minecraft_uuid: data.minecraftUuid ?? null,
      })
      .onConflictDoUpdate({
        target: players.discord_id,
        set: {
          discord_username: sql`excluded.discord_username`,
          minecraft_username: sql`COALESCE(excluded.minecraft_username, ${players.minecraft_username})`,
          minecraft_uuid: sql`COALESCE(excluded.minecraft_uuid, ${players.minecraft_uuid})`,
          updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
        },
      });
  });
}

export async function createApplication(
  data: CreateApplicationData,
): Promise<number> {
  // Applications are unique per (discord_id, season). A re-application for
  // the same season (e.g. after a denial) upserts onto the existing row and
  // resets it to a fresh pending state. When season is null the unique index
  // treats the rows as distinct, so this simply inserts a new row.
  const now = Math.floor(Date.now() / 1000);
  const [row] = await db
    .insert(applications)
    .values({
      discord_id: data.discordId,
      discord_username: data.discordUsername,
      minecraft_username: data.minecraftUsername,
      minecraft_uuid: data.minecraftUuid ?? null,
      age_met: data.ageMet,
      voice_chat: data.voiceChat,
      join_reason: data.joinReason ?? null,
      favourite_wood: data.favouriteWood ?? null,
      season: data.season ?? null,
    })
    .onConflictDoUpdate({
      target: [applications.discord_id, applications.season],
      set: {
        discord_username: sql`excluded.discord_username`,
        minecraft_username: sql`excluded.minecraft_username`,
        minecraft_uuid: sql`excluded.minecraft_uuid`,
        age_met: sql`excluded.age_met`,
        voice_chat: sql`excluded.voice_chat`,
        join_reason: sql`excluded.join_reason`,
        favourite_wood: sql`excluded.favourite_wood`,
        status: "pending",
        policy_agreed: false,
        denial_reason: null,
        resolved_at: null,
        resolved_by_discord_id: null,
        applied_at: now,
      },
    })
    .returning({ id: applications.id });
  return row.id;
}

export async function setPolicyAgreed(
  discordId: string,
  agreed: boolean,
): Promise<void> {
  // Find the latest pending application for this user, then update it
  const [latest] = await db
    .select({ id: applications.id })
    .from(applications)
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    )
    .orderBy(desc(applications.applied_at))
    .limit(1);

  if (latest) {
    await db
      .update(applications)
      .set({ policy_agreed: agreed })
      .where(eq(applications.id, latest.id));
  }
}

export async function hasPendingApplication(
  discordId: string,
): Promise<boolean> {
  const rows = await db
    .select({ id: applications.id })
    .from(applications)
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    )
    .limit(1);
  return rows.length > 0;
}

/**
 * Atomically transition the applicant's pending application to accepted.
 * Returns true only if a pending row was actually updated — callers use this
 * as a lock to guard against two moderators accepting at the same time.
 */
export async function acceptApplication(
  discordId: string,
  resolvedBy: string,
): Promise<boolean> {
  const now = Math.floor(Date.now() / 1000);
  const updated = await db
    .update(applications)
    .set({
      status: "accepted",
      resolved_at: now,
      resolved_by_discord_id: resolvedBy,
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    )
    .returning({ id: applications.id });
  return updated.length > 0;
}

/**
 * Roll an accepted application back to pending. Used to undo the accept-time
 * status flip (the double-accept guard) when a later step — e.g. the whitelist
 * insert — fails, so a moderator can simply retry.
 */
export async function revertApplicationToPending(
  discordId: string,
): Promise<void> {
  await db
    .update(applications)
    .set({
      status: "pending",
      resolved_at: null,
      resolved_by_discord_id: null,
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "accepted"),
      ),
    );
}

export async function cancelPendingApplications(
  discordId: string,
): Promise<void> {
  await db
    .update(applications)
    .set({
      status: "cancelled",
      resolved_at: Math.floor(Date.now() / 1000),
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    );
}

export async function denyApplication(
  discordId: string,
  reason: string,
  resolvedBy: string,
): Promise<void> {
  await db
    .update(applications)
    .set({
      status: "denied",
      denial_reason: reason,
      resolved_at: Math.floor(Date.now() / 1000),
      resolved_by_discord_id: resolvedBy,
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    );
}

export async function getLatestApplication(discordId: string) {
  const rows = await db
    .select()
    .from(applications)
    .where(eq(applications.discord_id, discordId))
    .orderBy(desc(applications.applied_at))
    .limit(1);
  return rows[0] ?? null;
}

export async function updateApplication(
  discordId: string,
  data: {
    minecraftUsername: string;
    minecraftUuid: string;
    ageMet: boolean;
    voiceChat: boolean;
    joinReason?: string;
    favouriteWood?: string;
  },
): Promise<void> {
  await db
    .update(applications)
    .set({
      minecraft_username: data.minecraftUsername,
      minecraft_uuid: data.minecraftUuid,
      age_met: data.ageMet,
      voice_chat: data.voiceChat,
      join_reason: data.joinReason ?? null,
      favourite_wood: data.favouriteWood ?? null,
    })
    .where(
      and(
        eq(applications.discord_id, discordId),
        eq(applications.status, "pending"),
      ),
    );
}

// ── Stream channels ────────────────────────────────────────────

export type Platform = "youtube" | "twitch" | "tiktok";

export interface StreamChannel {
  id: number;
  platform: Platform;
  channel_id: string;
  discord_user_id: string;
  display_name: string | null;
}

export async function addStreamChannel(
  platform: Platform,
  channelId: string,
  discordUserId: string,
  displayName?: string,
): Promise<void> {
  await db
    .insert(streamChannels)
    .values({
      platform,
      channel_id: channelId,
      discord_user_id: discordUserId,
      display_name: displayName ?? null,
    })
    .onConflictDoUpdate({
      target: [streamChannels.platform, streamChannels.channel_id],
      set: {
        discord_user_id: sql`excluded.discord_user_id`,
        display_name: sql`COALESCE(excluded.display_name, ${streamChannels.display_name})`,
      },
    });
}

export async function removeStreamChannel(
  platform: Platform,
  channelId: string,
): Promise<boolean> {
  const result = await db
    .delete(streamChannels)
    .where(
      and(
        eq(streamChannels.platform, platform),
        eq(streamChannels.channel_id, channelId),
      ),
    );
  return (result as any).rowCount > 0;
}

export async function getAllStreamChannels(): Promise<StreamChannel[]> {
  const rows = await db.select().from(streamChannels);
  return rows.map((r) => ({
    id: r.id,
    platform: r.platform as Platform,
    channel_id: r.channel_id,
    discord_user_id: r.discord_user_id,
    display_name: r.display_name,
  }));
}

export async function getStreamChannelsByPlatform(
  platform: Platform,
): Promise<StreamChannel[]> {
  const rows = await db
    .select()
    .from(streamChannels)
    .where(eq(streamChannels.platform, platform));
  return rows.map((r) => ({
    id: r.id,
    platform: r.platform as Platform,
    channel_id: r.channel_id,
    discord_user_id: r.discord_user_id,
    display_name: r.display_name,
  }));
}

// ── Player alts ────────────────────────────────────────────────

export const MAX_ALTS = 2;

export interface PlayerAlt {
  id: number;
  discord_id: string;
  minecraft_uuid: string;
  minecraft_username: string;
  created_at: number;
}

export async function addPlayerAlt(
  discordId: string,
  minecraftUuid: string,
  minecraftUsername: string,
): Promise<void> {
  await db.insert(playerAlts).values({
    discord_id: discordId,
    minecraft_uuid: minecraftUuid,
    minecraft_username: minecraftUsername,
  });
}

export async function removePlayerAlt(
  discordId: string,
  minecraftUuid: string,
): Promise<boolean> {
  const result = await db
    .delete(playerAlts)
    .where(
      and(
        eq(playerAlts.discord_id, discordId),
        eq(playerAlts.minecraft_uuid, minecraftUuid),
      ),
    );
  return (result as any).rowCount > 0;
}

export async function getPlayerAlts(discordId: string): Promise<PlayerAlt[]> {
  const rows = await db
    .select()
    .from(playerAlts)
    .where(eq(playerAlts.discord_id, discordId));
  return rows as PlayerAlt[];
}

export async function getAltCountForUser(discordId: string): Promise<number> {
  const rows = await db
    .select({ count: sql<number>`COUNT(*)::INTEGER` })
    .from(playerAlts)
    .where(eq(playerAlts.discord_id, discordId));
  return rows[0]?.count ?? 0;
}

export async function deleteAllAltsForUser(discordId: string): Promise<void> {
  await db.delete(playerAlts).where(eq(playerAlts.discord_id, discordId));
}

export async function getPlayerPrimaryUuid(discordId: string): Promise<string | null> {
  const rows = await db
    .select({ minecraft_uuid: players.minecraft_uuid })
    .from(players)
    .where(eq(players.discord_id, discordId))
    .limit(1);
  return rows[0]?.minecraft_uuid ?? null;
}

export async function isAltUuidTaken(minecraftUuid: string): Promise<boolean> {
  const rows = await db
    .select({ id: playerAlts.id })
    .from(playerAlts)
    .where(eq(playerAlts.minecraft_uuid, minecraftUuid))
    .limit(1);
  return rows.length > 0;
}

// ── Starboard ──────────────────────────────────────────────────

export interface StarboardPost {
  message_id: string;
  channel_id: string;
  author_id: string;
  starboard_message_id: string | null;
  trigger_emoji_id: string | null;
  trigger_emoji_name: string | null;
  trigger_emoji_animated: boolean;
  posted_at: number;
}

export async function hasStarboardPost(messageId: string): Promise<boolean> {
  const rows = await db
    .select({ message_id: starboardPosts.message_id })
    .from(starboardPosts)
    .where(eq(starboardPosts.message_id, messageId))
    .limit(1);
  return rows.length > 0;
}

export async function getStarboardPost(
  messageId: string,
): Promise<StarboardPost | null> {
  const [row] = await db
    .select()
    .from(starboardPosts)
    .where(eq(starboardPosts.message_id, messageId))
    .limit(1);
  return (row as StarboardPost | undefined) ?? null;
}

/**
 * Atomically claims a message for starboard reposting. Returns `true`
 * if this caller won the race and should send the starboard message,
 * `false` if another caller already claimed it.
 */
export async function claimStarboardPost(data: {
  messageId: string;
  channelId: string;
  authorId: string;
  triggerEmojiId: string | null;
  triggerEmojiName: string | null;
  triggerEmojiAnimated: boolean;
}): Promise<boolean> {
  const inserted = await db
    .insert(starboardPosts)
    .values({
      message_id: data.messageId,
      channel_id: data.channelId,
      author_id: data.authorId,
      trigger_emoji_id: data.triggerEmojiId,
      trigger_emoji_name: data.triggerEmojiName,
      trigger_emoji_animated: data.triggerEmojiAnimated,
    })
    .onConflictDoNothing()
    .returning({ message_id: starboardPosts.message_id });
  return inserted.length > 0;
}

export async function setStarboardMessageId(
  messageId: string,
  starboardMessageId: string,
): Promise<void> {
  await db
    .update(starboardPosts)
    .set({ starboard_message_id: starboardMessageId })
    .where(eq(starboardPosts.message_id, messageId));
}

export async function deleteStarboardPost(messageId: string): Promise<void> {
  await db
    .delete(starboardPosts)
    .where(eq(starboardPosts.message_id, messageId));
}

export async function getStarboardPostsByAuthor(
  authorId: string,
): Promise<StarboardPost[]> {
  const rows = await db
    .select()
    .from(starboardPosts)
    .where(eq(starboardPosts.author_id, authorId))
    .orderBy(desc(starboardPosts.posted_at));
  return rows as StarboardPost[];
}

// ── Counting ───────────────────────────────────────────────────

export interface CountingState {
  channel_id: string;
  current_count: number;
  last_user_id: string | null;
  updated_at: number;
}

export async function getCountingState(
  channelId: string,
): Promise<CountingState | null> {
  const [row] = await db
    .select()
    .from(countingState)
    .where(eq(countingState.channel_id, channelId))
    .limit(1);
  return (row as CountingState | undefined) ?? null;
}

/**
 * Atomically advances the count by 1 only when the row's
 * `current_count` still equals `expectedCurrent` AND the last
 * counter wasn't this user. Returns true if the row was updated.
 */
export async function tryAdvanceCount(
  channelId: string,
  expectedCurrent: number,
  userId: string,
): Promise<boolean> {
  const now = Math.floor(Date.now() / 1000);
  const updated = await db
    .update(countingState)
    .set({
      current_count: expectedCurrent + 1,
      last_user_id: userId,
      updated_at: now,
    })
    .where(
      and(
        eq(countingState.channel_id, channelId),
        eq(countingState.current_count, expectedCurrent),
        sql`(${countingState.last_user_id} IS NULL OR ${countingState.last_user_id} <> ${userId})`,
      ),
    )
    .returning({ channel_id: countingState.channel_id });
  return updated.length > 0;
}

/** Mod-only seed/override. Upserts the counting state row. */
export async function setCountingState(
  channelId: string,
  count: number,
  lastUserId: string | null = null,
): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  await db
    .insert(countingState)
    .values({
      channel_id: channelId,
      current_count: count,
      last_user_id: lastUserId,
      updated_at: now,
    })
    .onConflictDoUpdate({
      target: countingState.channel_id,
      set: {
        current_count: count,
        last_user_id: lastUserId,
        updated_at: now,
      },
    });
}

// ── Tickets ────────────────────────────────────────────────────

/** Max simultaneous open tickets per category per user. */
export const MAX_OPEN_TICKETS_PER_CATEGORY = 3;

export interface CreateTicketData {
  channelId: string;
  parentCategoryId: string;
  guildId: string;
  openerDiscordId: string;
  openerDiscordUsername: string;
  openerMinecraftUuid?: string | null;
  openerMinecraftUsername?: string | null;
  category: TicketCategory;
  subject?: string | null;
  intake: Record<string, unknown>;
}

export type Ticket = typeof tickets.$inferSelect;

export async function createTicket(data: CreateTicketData): Promise<Ticket> {
  const [row] = await db
    .insert(tickets)
    .values({
      channel_id: data.channelId,
      parent_category_id: data.parentCategoryId,
      guild_id: data.guildId,
      opener_discord_id: data.openerDiscordId,
      opener_discord_username: data.openerDiscordUsername,
      opener_minecraft_uuid: data.openerMinecraftUuid ?? null,
      opener_minecraft_username: data.openerMinecraftUsername ?? null,
      category: data.category,
      subject: data.subject ?? null,
      intake: data.intake,
    })
    .returning();
  return row;
}

export async function getTicketByChannelId(
  channelId: string,
): Promise<Ticket | null> {
  const [row] = await db
    .select()
    .from(tickets)
    .where(eq(tickets.channel_id, channelId))
    .limit(1);
  return row ?? null;
}

export async function getTicketById(id: number): Promise<Ticket | null> {
  const [row] = await db
    .select()
    .from(tickets)
    .where(eq(tickets.id, id))
    .limit(1);
  return row ?? null;
}

export async function countOpenTicketsForUserAndCategory(
  discordId: string,
  category: TicketCategory,
): Promise<number> {
  const rows = await db
    .select({ count: sql<number>`COUNT(*)::INTEGER` })
    .from(tickets)
    .where(
      and(
        eq(tickets.opener_discord_id, discordId),
        eq(tickets.category, category),
        eq(tickets.status, "open"),
      ),
    );
  return rows[0]?.count ?? 0;
}

export async function closeTicket(
  ticketId: number,
  closedByDiscordId: string,
  deleteAfter: number,
): Promise<Ticket | null> {
  const now = Math.floor(Date.now() / 1000);
  const [row] = await db
    .update(tickets)
    .set({
      status: "closed",
      closed_by_discord_id: closedByDiscordId,
      closed_at: now,
      delete_after: deleteAfter,
      updated_at: now,
    })
    .where(eq(tickets.id, ticketId))
    .returning();
  return row ?? null;
}

export async function reopenTicket(ticketId: number): Promise<Ticket | null> {
  const now = Math.floor(Date.now() / 1000);
  const [row] = await db
    .update(tickets)
    .set({
      status: "open",
      closed_by_discord_id: null,
      closed_at: null,
      delete_after: null,
      updated_at: now,
    })
    .where(eq(tickets.id, ticketId))
    .returning();
  return row ?? null;
}

export async function getExpiredClosedTickets(now: number): Promise<Ticket[]> {
  return db
    .select()
    .from(tickets)
    .where(
      and(
        eq(tickets.status, "closed"),
        lte(tickets.delete_after, now),
      ),
    );
}

export async function deleteTicketRow(ticketId: number): Promise<void> {
  await db.delete(tickets).where(eq(tickets.id, ticketId));
}

export async function listOpenTicketsForUser(
  discordId: string,
): Promise<Ticket[]> {
  return db
    .select()
    .from(tickets)
    .where(
      and(
        eq(tickets.opener_discord_id, discordId),
        eq(tickets.status, "open"),
      ),
    )
    .orderBy(desc(tickets.created_at));
}

/** Minimal player lookup used to populate the ticket header card. */
export async function getPlayerLink(
  discordId: string,
): Promise<{
  minecraft_username: string | null;
  minecraft_uuid: string | null;
} | null> {
  const [row] = await db
    .select({
      minecraft_username: players.minecraft_username,
      minecraft_uuid: players.minecraft_uuid,
    })
    .from(players)
    .where(eq(players.discord_id, discordId))
    .limit(1);
  return row ?? null;
}

// ── Identity sync ───────────────────────────────────────────────

export interface SyncablePlayerIdentity {
  discord_id: string;
  discord_username: string;
  minecraft_uuid: string | null;
  minecraft_username: string | null;
}

export interface SyncablePlayerAltIdentity {
  minecraft_uuid: string;
  minecraft_username: string;
}

export async function getSyncablePlayerIdentities(): Promise<SyncablePlayerIdentity[]> {
  return db
    .select({
      discord_id: players.discord_id,
      discord_username: players.discord_username,
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
    })
    .from(players);
}

export async function getSyncablePlayerAltIdentities(): Promise<SyncablePlayerAltIdentity[]> {
  return db
    .select({
      minecraft_uuid: playerAlts.minecraft_uuid,
      minecraft_username: playerAlts.minecraft_username,
    })
    .from(playerAlts);
}

export interface PunishmentRoleSyncAccount {
  discord_id: string;
  minecraft_uuid: string;
}

export async function getPunishmentRoleSyncAccounts(): Promise<PunishmentRoleSyncAccount[]> {
  const primaryRows = await db
    .select({
      discord_id: players.discord_id,
      minecraft_uuid: players.minecraft_uuid,
    })
    .from(players)
    .where(isNotNull(players.minecraft_uuid));

  const altRows = await db
    .select({
      discord_id: playerAlts.discord_id,
      minecraft_uuid: playerAlts.minecraft_uuid,
    })
    .from(playerAlts);

  return [
    ...primaryRows.filter(
      (row): row is PunishmentRoleSyncAccount => row.minecraft_uuid !== null,
    ),
    ...altRows,
  ];
}

export async function updatePlayerDiscordUsername(
  discordId: string,
  discordUsername: string,
): Promise<void> {
  await db
    .update(players)
    .set({
      discord_username: discordUsername,
      updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
    })
    .where(eq(players.discord_id, discordId));
}

export async function updatePlayerMinecraftUsername(
  minecraftUuid: string,
  minecraftUsername: string,
): Promise<void> {
  await db
    .update(players)
    .set({
      minecraft_username: minecraftUsername,
      updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
    })
    .where(eq(players.minecraft_uuid, minecraftUuid));
}

export async function updateAltMinecraftUsername(
  minecraftUuid: string,
  minecraftUsername: string,
): Promise<void> {
  await db
    .update(playerAlts)
    .set({ minecraft_username: minecraftUsername })
    .where(eq(playerAlts.minecraft_uuid, minecraftUuid));
}

/**
 * Unlink a Minecraft account from whichever player row currently holds it,
 * so the account can be re-linked (e.g. as another user's alt). Returns the
 * discord_id of the row that was unlinked, or null if no row held the uuid.
 */
export async function clearPlayerMinecraftLinkByUuid(
  uuid: string,
): Promise<string | null> {
  const [row] = await db
    .update(players)
    .set({
      minecraft_uuid: null,
      minecraft_username: null,
      updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
    })
    .where(eq(players.minecraft_uuid, uuid))
    .returning({ discord_id: players.discord_id });
  return row?.discord_id ?? null;
}

/** Unlink a Discord user's Minecraft account (keeps the player row). */
export async function clearPlayerMinecraftLinkByDiscordId(
  discordId: string,
): Promise<void> {
  await db
    .update(players)
    .set({
      minecraft_uuid: null,
      minecraft_username: null,
      updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
    })
    .where(eq(players.discord_id, discordId));
}

// ── player profile card (/playerinfo) ───────────────────────────

export interface PlayerIdentity {
  minecraft_uuid: string;
  minecraft_username: string | null;
  discord_username: string;
  role: string;
}

const PLAYER_IDENTITY_COLUMNS = {
  minecraft_uuid: players.minecraft_uuid,
  minecraft_username: players.minecraft_username,
  discord_username: players.discord_username,
  role: players.role,
} as const;

/** Resolve a linked player by Minecraft username (case-insensitive). */
export async function getPlayerByMinecraftUsername(
  username: string,
): Promise<PlayerIdentity | null> {
  const [row] = await db
    .select(PLAYER_IDENTITY_COLUMNS)
    .from(players)
    .where(ilike(players.minecraft_username, username))
    .limit(1);
  if (!row?.minecraft_uuid) return null;
  return row as PlayerIdentity;
}

/** The linked Discord id for a Minecraft UUID, if any. */
export async function getDiscordIdByMinecraftUuid(
  uuid: string,
): Promise<string | null> {
  const [row] = await db
    .select({ discord_id: players.discord_id })
    .from(players)
    .where(eq(players.minecraft_uuid, uuid))
    .limit(1);
  return row?.discord_id ?? null;
}

/** The linked Discord id for a Minecraft username (case-insensitive), if any. */
export async function getDiscordIdByMinecraftUsername(
  username: string,
): Promise<string | null> {
  const [row] = await db
    .select({ discord_id: players.discord_id })
    .from(players)
    .where(ilike(players.minecraft_username, username))
    .limit(1);
  return row?.discord_id ?? null;
}

/** Resolve a linked player by Minecraft UUID. */
export async function getPlayerByMinecraftUuid(
  uuid: string,
): Promise<PlayerIdentity | null> {
  const [row] = await db
    .select(PLAYER_IDENTITY_COLUMNS)
    .from(players)
    .where(eq(players.minecraft_uuid, uuid))
    .limit(1);
  if (!row?.minecraft_uuid) return null;
  return row as PlayerIdentity;
}

/** The currently active season, or null if none is flagged current. */
export async function getCurrentSeason(): Promise<{ id: string; name: string } | null> {
  const [row] = await db
    .select({ id: seasons.id, name: seasons.name })
    .from(seasons)
    .where(eq(seasons.is_current, true))
    .limit(1);
  return row ?? null;
}

/**
 * Search linked players by Minecraft username substring (case-insensitive) for
 * slash-command autocomplete. An empty query returns the first `limit` players
 * alphabetically. LIKE metacharacters in the query are escaped.
 */
export async function searchPlayersByUsername(
  query: string,
  limit = 25,
): Promise<{ minecraft_uuid: string; minecraft_username: string }[]> {
  const rows = await db
    .select({
      minecraft_uuid: players.minecraft_uuid,
      minecraft_username: players.minecraft_username,
    })
    .from(players)
    .where(ilike(players.minecraft_username, `%${query.replace(/[%_\\]/g, (c) => "\\" + c)}%`))
    .orderBy(asc(players.minecraft_username))
    .limit(limit);
  return rows.filter(
    (r): r is { minecraft_uuid: string; minecraft_username: string } =>
      r.minecraft_uuid !== null && r.minecraft_username !== null,
  );
}

// ── Application channels ────────────────────────────────────────

export type ApplicationChannel = typeof applicationChannels.$inferSelect;

export interface CreateApplicationChannelData {
  channelId: string;
  applicantId: string;
  applicantUsername: string;
  guildId: string;
}

/**
 * Record an application channel. Upserts on the channel id so a reused
 * channel cleanly refreshes (and resets the reminder/deletion state).
 */
export async function createApplicationChannel(
  data: CreateApplicationChannelData,
): Promise<ApplicationChannel> {
  const [row] = await db
    .insert(applicationChannels)
    .values({
      channel_id: data.channelId,
      applicant_id: data.applicantId,
      applicant_username: data.applicantUsername,
      guild_id: data.guildId,
    })
    .onConflictDoUpdate({
      target: applicationChannels.channel_id,
      set: {
        applicant_id: sql`excluded.applicant_id`,
        applicant_username: sql`excluded.applicant_username`,
        reminded: false,
        delete_after: null,
        updated_at: sql`EXTRACT(EPOCH FROM NOW())::INTEGER`,
      },
    })
    .returning();
  return row;
}

export async function getApplicationChannelByChannelId(
  channelId: string,
): Promise<ApplicationChannel | null> {
  const [row] = await db
    .select()
    .from(applicationChannels)
    .where(eq(applicationChannels.channel_id, channelId))
    .limit(1);
  return row ?? null;
}

/** The most recent application channel opened for a given applicant. */
export async function getApplicationChannelByApplicant(
  applicantId: string,
): Promise<ApplicationChannel | null> {
  const [row] = await db
    .select()
    .from(applicationChannels)
    .where(eq(applicationChannels.applicant_id, applicantId))
    .orderBy(desc(applicationChannels.created_at))
    .limit(1);
  return row ?? null;
}

export async function markApplicationChannelReminded(
  channelId: string,
): Promise<void> {
  await db
    .update(applicationChannels)
    .set({ reminded: true, updated_at: Math.floor(Date.now() / 1000) })
    .where(eq(applicationChannels.channel_id, channelId));
}

export async function setApplicationChannelDeleteAfter(
  channelId: string,
  deleteAfter: number,
): Promise<void> {
  await db
    .update(applicationChannels)
    .set({
      delete_after: deleteAfter,
      updated_at: Math.floor(Date.now() / 1000),
    })
    .where(eq(applicationChannels.channel_id, channelId));
}

/**
 * Application channels due a "you haven't applied yet" reminder: created
 * before `createdBefore` (unix seconds), not yet reminded, and not already
 * scheduled for deletion (i.e. still awaiting an application).
 */
export async function getApplicationChannelsNeedingReminder(
  createdBefore: number,
): Promise<ApplicationChannel[]> {
  return db
    .select()
    .from(applicationChannels)
    .where(
      and(
        eq(applicationChannels.reminded, false),
        isNull(applicationChannels.delete_after),
        lte(applicationChannels.created_at, createdBefore),
      ),
    );
}

/**
 * Live application channels (no decision yet) created before `createdBefore`
 * (unix seconds). Used to sweep channels of applicants who never submitted.
 */
export async function getApplicationChannelsOlderThan(
  createdBefore: number,
): Promise<ApplicationChannel[]> {
  return db
    .select()
    .from(applicationChannels)
    .where(
      and(
        isNull(applicationChannels.delete_after),
        lte(applicationChannels.created_at, createdBefore),
      ),
    );
}

/** Channels whose post-decision deletion window has elapsed. */
export async function getExpiredApplicationChannels(
  now: number,
): Promise<ApplicationChannel[]> {
  return db
    .select()
    .from(applicationChannels)
    .where(lte(applicationChannels.delete_after, now));
}

export async function deleteApplicationChannelRow(
  channelId: string,
): Promise<void> {
  await db
    .delete(applicationChannels)
    .where(eq(applicationChannels.channel_id, channelId));
}
