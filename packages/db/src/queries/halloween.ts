import { and, eq, inArray, isNotNull, isNull, sql } from "drizzle-orm";
import { db } from "../client";
import { halloweenEvents, halloweenPlayerProgress, playerAlts, players } from "../schema";
import { advanceHalloweenProgress, isHalloweenComplete, isNewerStreamId, type HalloweenAction } from "../halloweenLogic";

export async function configureHalloweenEvent(event: typeof halloweenEvents.$inferInsert) {
  await db.insert(halloweenEvents).values(event).onConflictDoUpdate({
    target: halloweenEvents.id,
    set: { starts_at: event.starts_at, ends_at: event.ends_at, guild_id: event.guild_id, role_id: event.role_id },
  });
}

export async function recordHalloweenAction(event: {
  eventId: string;
  minecraftUuid: string;
  action: HalloweenAction;
  occurredAt: number;
}, streamId: string) {
  return db.transaction(async (tx) => {
    const [schedule] = await tx.select().from(halloweenEvents).where(eq(halloweenEvents.id, event.eventId));
    if (!schedule || event.occurredAt < schedule.starts_at || event.occurredAt >= schedule.ends_at) return;
    const key = and(eq(halloweenPlayerProgress.event_id, event.eventId),
      eq(halloweenPlayerProgress.minecraft_uuid, event.minecraftUuid));
    await tx.insert(halloweenPlayerProgress).values({
      event_id: event.eventId, minecraft_uuid: event.minecraftUuid,
    }).onConflictDoNothing();
    const [previous] = await tx.select().from(halloweenPlayerProgress).where(key).for("update");
    if (!isNewerStreamId(streamId, previous.last_stream_id)) return;
    const next = advanceHalloweenProgress(previous, event.action);
    await tx.update(halloweenPlayerProgress).set({
      hunt_mask: next.hunt_mask,
      trick_or_treat: next.trick_or_treat,
      back_from_the_dead: next.back_from_the_dead,
      last_stream_id: streamId,
      completed_at: previous.completed_at ?? (isHalloweenComplete(next) ? event.occurredAt : null),
    }).where(key);
    // Only a persisted incomplete -> complete transition produces a chat notification.
    const task = previous.hunt_mask !== 31 && next.hunt_mask === 31 ? "pumpkin_hunt"
      : !previous.trick_or_treat && next.trick_or_treat ? "trick_or_treat"
      : !previous.back_from_the_dead && next.back_from_the_dead ? "back_from_the_dead" : null;
    if (!task) return;
    return {
      eventId: event.eventId, minecraftUuid: event.minecraftUuid, task,
      completedTasks: Number(next.hunt_mask === 31) + Number(next.trick_or_treat) + Number(next.back_from_the_dead),
    };
  });
}

export async function getPendingHalloweenRoles() {
  return db.select({
    eventId: halloweenPlayerProgress.event_id,
    minecraftUuid: halloweenPlayerProgress.minecraft_uuid,
    guildId: halloweenEvents.guild_id,
    roleId: halloweenEvents.role_id,
    discordId: sql<string | null>`COALESCE(${players.discord_id}, ${playerAlts.discord_id})`,
  }).from(halloweenPlayerProgress)
    .innerJoin(halloweenEvents, eq(halloweenEvents.id, halloweenPlayerProgress.event_id))
    .leftJoin(players, eq(players.minecraft_uuid, halloweenPlayerProgress.minecraft_uuid))
    .leftJoin(playerAlts, eq(playerAlts.minecraft_uuid, halloweenPlayerProgress.minecraft_uuid))
    .where(and(isNotNull(halloweenPlayerProgress.completed_at), isNull(halloweenPlayerProgress.role_awarded_at)));
}

export async function markHalloweenRoleAwarded(eventId: string, minecraftUuid: string) {
  await db.update(halloweenPlayerProgress).set({ role_awarded_at: Math.floor(Date.now() / 1000) })
    .where(and(eq(halloweenPlayerProgress.event_id, eventId), eq(halloweenPlayerProgress.minecraft_uuid, minecraftUuid)));
}

export async function getHalloweenProgress(discordId: string, eventId: string) {
  const [primary] = await db.select({ uuid: players.minecraft_uuid, username: players.minecraft_username })
    .from(players).where(eq(players.discord_id, discordId));
  const alts = await db.select({ uuid: playerAlts.minecraft_uuid, username: playerAlts.minecraft_username })
    .from(playerAlts).where(eq(playerAlts.discord_id, discordId));
  const accounts = [...(primary?.uuid ? [primary] : []), ...alts];
  if (!accounts.length) return [];
  const progress = await db.select().from(halloweenPlayerProgress).where(and(
    eq(halloweenPlayerProgress.event_id, eventId),
    inArray(halloweenPlayerProgress.minecraft_uuid, accounts.map((account) => account.uuid!)),
  ));
  return accounts.map((account) => ({
    username: account.username || account.uuid!,
    progress: progress.find((row) => row.minecraft_uuid === account.uuid) ?? {
      hunt_mask: 0, trick_or_treat: false, back_from_the_dead: false,
    },
  }));
}

/** Read the issuing Minecraft account directly; Discord linking is not required. */
export async function getMinecraftHalloweenProgress(eventId: string, minecraftUuid: string) {
  const [event] = await db.select().from(halloweenEvents).where(eq(halloweenEvents.id, eventId));
  if (!event) return null;
  const [progress] = await db.select().from(halloweenPlayerProgress).where(and(
    eq(halloweenPlayerProgress.event_id, eventId),
    eq(halloweenPlayerProgress.minecraft_uuid, minecraftUuid),
  ));
  return {
    startsAt: event.starts_at, endsAt: event.ends_at,
    huntMask: progress?.hunt_mask ?? 0,
    trickOrTreat: progress?.trick_or_treat ?? false,
    backFromTheDead: progress?.back_from_the_dead ?? false,
  };
}
