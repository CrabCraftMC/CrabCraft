import { eq, and, desc, sql, inArray, lte } from "drizzle-orm";
import { db } from "../client";
import {
  players,
  applications,
  streamChannels,
  playerAlts,
  tickets,
  type TicketCategory,
  type TicketStatus,
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
  over15: boolean;
  voiceChat: boolean;
  joinReason?: string;
  favouriteWood?: string;
  season?: string | null;
}

export async function upsertUser(data: UpsertUserData): Promise<void> {
  await db
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
}

export async function createApplication(
  data: CreateApplicationData,
): Promise<number> {
  const [row] = await db
    .insert(applications)
    .values({
      discord_id: data.discordId,
      discord_username: data.discordUsername,
      minecraft_username: data.minecraftUsername,
      minecraft_uuid: data.minecraftUuid ?? null,
      over_15: data.over15,
      voice_chat: data.voiceChat,
      join_reason: data.joinReason ?? null,
      favourite_wood: data.favouriteWood ?? null,
      season: data.season ?? null,
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

export async function acceptApplication(
  discordId: string,
  resolvedBy: string,
): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  await db
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
    over15: boolean;
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
      over_15: data.over15,
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

// ── Tickets ────────────────────────────────────────────────────

/** Max simultaneous open tickets per category per user. */
export const MAX_OPEN_TICKETS_PER_CATEGORY = 1;

const ACTIVE_TICKET_STATUSES = ["open", "claimed"] as const satisfies readonly TicketStatus[];

export interface CreateTicketData {
  threadId: string;
  parentChannelId: string;
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
      thread_id: data.threadId,
      parent_channel_id: data.parentChannelId,
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

export async function getTicketByThreadId(
  threadId: string,
): Promise<Ticket | null> {
  const [row] = await db
    .select()
    .from(tickets)
    .where(eq(tickets.thread_id, threadId))
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
        inArray(tickets.status, ACTIVE_TICKET_STATUSES as unknown as TicketStatus[]),
      ),
    );
  return rows[0]?.count ?? 0;
}

export async function claimTicket(
  ticketId: number,
  moderatorDiscordId: string,
): Promise<Ticket | null> {
  const now = Math.floor(Date.now() / 1000);
  const [row] = await db
    .update(tickets)
    .set({
      status: "claimed",
      claimed_by_discord_id: moderatorDiscordId,
      claimed_at: now,
      updated_at: now,
    })
    .where(
      and(
        eq(tickets.id, ticketId),
        inArray(tickets.status, ACTIVE_TICKET_STATUSES as unknown as TicketStatus[]),
      ),
    )
    .returning();
  return row ?? null;
}

export async function closeTicket(
  ticketId: number,
  closedByDiscordId: string,
  reason: string | null,
  deleteAfter: number,
): Promise<Ticket | null> {
  const now = Math.floor(Date.now() / 1000);
  const [row] = await db
    .update(tickets)
    .set({
      status: "closed",
      closed_by_discord_id: closedByDiscordId,
      closed_at: now,
      close_reason: reason,
      delete_after: deleteAfter,
      updated_at: now,
    })
    .where(eq(tickets.id, ticketId))
    .returning();
  return row ?? null;
}

export async function reopenTicket(ticketId: number): Promise<Ticket | null> {
  const now = Math.floor(Date.now() / 1000);
  // Restore to "claimed" if it was previously claimed, otherwise "open".
  const [row] = await db
    .update(tickets)
    .set({
      status: sql`CASE WHEN ${tickets.claimed_by_discord_id} IS NOT NULL THEN 'claimed'::ticket_status ELSE 'open'::ticket_status END`,
      closed_by_discord_id: null,
      closed_at: null,
      close_reason: null,
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
        inArray(tickets.status, ACTIVE_TICKET_STATUSES as unknown as TicketStatus[]),
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
