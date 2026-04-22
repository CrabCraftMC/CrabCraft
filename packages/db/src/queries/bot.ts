import { eq, and, desc, sql } from "drizzle-orm";
import { db } from "../client";
import { players, applications, streamChannels } from "../schema";

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
