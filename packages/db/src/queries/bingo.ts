import { and, asc, eq, isNotNull, isNull, lte, gt, or, sql } from "drizzle-orm";
import { db } from "../client";
import {
  bingoCards,
  bingoPlayerMilestones,
  bingoPlayerProgress,
  playerAlts,
  players,
} from "../schema";
import { hasBingoBlackout, hasBingoLine } from "../bingoLogic";

export interface BingoTaskDefinition {
  id: string;
  label: string;
}

export interface BingoCardRecord {
  id: number;
  number: number;
  starts_at: number;
  ends_at: number;
  tasks: BingoTaskDefinition[];
  announcement_guild_id: string | null;
  announcement_channel_id: string | null;
  announcement_message_id: string | null;
  posted_at: number | null;
}

export async function seedBingoCard(card: {
  number: number;
  startsAt: number;
  endsAt: number;
  tasks: BingoTaskDefinition[];
}): Promise<void> {
  const values = {
    starts_at: card.startsAt,
    ends_at: card.endsAt,
    tasks: card.tasks,
  };
  await db
    .insert(bingoCards)
    .values({
      number: card.number,
      ...values,
    })
    .onConflictDoNothing({ target: bingoCards.number });

  // Prepared cards remain editable until their Discord announcement is sent.
  await db
    .update(bingoCards)
    .set(values)
    .where(and(eq(bingoCards.number, card.number), isNull(bingoCards.posted_at)));
}

export async function getActiveBingoCard(now: number): Promise<BingoCardRecord | null> {
  const [card] = await db
    .select()
    .from(bingoCards)
    .where(and(lte(bingoCards.starts_at, now), gt(bingoCards.ends_at, now)))
    .orderBy(asc(bingoCards.starts_at))
    .limit(1);
  return (card as BingoCardRecord | undefined) ?? null;
}

export async function getBingoCardStartingAt(startsAt: number): Promise<BingoCardRecord | null> {
  const [card] = await db
    .select()
    .from(bingoCards)
    .where(eq(bingoCards.starts_at, startsAt))
    .limit(1);
  return (card as BingoCardRecord | undefined) ?? null;
}

export async function markBingoCardPosted(
  cardId: number,
  guildId: string,
  channelId: string,
  messageId: string,
): Promise<void> {
  await db
    .update(bingoCards)
    .set({
      announcement_guild_id: guildId,
      announcement_channel_id: channelId,
      announcement_message_id: messageId,
      posted_at: Math.floor(Date.now() / 1000),
    })
    .where(and(eq(bingoCards.id, cardId), isNull(bingoCards.posted_at)));
}

export async function recordBingoCompletion(event: {
  cardId: number;
  minecraftUuid: string;
  taskId: string;
  completedAt: number;
  sourceBackend: string | null;
}): Promise<boolean> {
  return db.transaction(async (tx) => {
    const [card] = await tx
      .select()
      .from(bingoCards)
      .where(eq(bingoCards.id, event.cardId))
      .limit(1);
    if (!card
      || event.completedAt < card.starts_at
      || event.completedAt >= card.ends_at
      || !card.tasks.some((task) => task.id === event.taskId)) return false;

    let ownerUuid = event.minecraftUuid;
    const [primary] = await tx
      .select({ uuid: players.minecraft_uuid })
      .from(players)
      .where(eq(players.minecraft_uuid, event.minecraftUuid))
      .limit(1);
    if (!primary?.uuid) {
      const [altOwner] = await tx
        .select({ uuid: players.minecraft_uuid })
        .from(playerAlts)
        .innerJoin(players, eq(players.discord_id, playerAlts.discord_id))
        .where(eq(playerAlts.minecraft_uuid, event.minecraftUuid))
        .limit(1);
      if (altOwner?.uuid) ownerUuid = altOwner.uuid;
    }

    const inserted = await tx
      .insert(bingoPlayerProgress)
      .values({
        card_id: event.cardId,
        minecraft_uuid: ownerUuid,
        source_minecraft_uuid: event.minecraftUuid,
        task_id: event.taskId,
        completed_at: event.completedAt,
        source_backend: event.sourceBackend,
      })
      .onConflictDoNothing()
      .returning({ taskId: bingoPlayerProgress.task_id });
    if (inserted.length === 0) return false;

    const rows = await tx
      .select({ taskId: bingoPlayerProgress.task_id })
      .from(bingoPlayerProgress)
      .where(and(
        eq(bingoPlayerProgress.card_id, event.cardId),
        eq(bingoPlayerProgress.minecraft_uuid, ownerUuid),
      ));
    const completed = new Set(rows.map((row) => row.taskId));
    const lineAt = hasBingoLine(card.tasks, completed) ? event.completedAt : null;
    const blackoutAt = hasBingoBlackout(card.tasks, completed)
      ? event.completedAt
      : null;
    if (lineAt === null && blackoutAt === null) return true;

    await tx
      .insert(bingoPlayerMilestones)
      .values({
        card_id: event.cardId,
        minecraft_uuid: ownerUuid,
        first_line_completed_at: lineAt,
        blackout_completed_at: blackoutAt,
      })
      .onConflictDoUpdate({
        target: [bingoPlayerMilestones.card_id, bingoPlayerMilestones.minecraft_uuid],
        set: {
          first_line_completed_at: sql`COALESCE(${bingoPlayerMilestones.first_line_completed_at}, excluded.first_line_completed_at)`,
          blackout_completed_at: sql`COALESCE(${bingoPlayerMilestones.blackout_completed_at}, excluded.blackout_completed_at)`,
        },
      });
    return true;
  });
}

export async function getBingoCardForDiscordUser(discordId: string, now: number) {
  const card = await getActiveBingoCard(now);
  if (!card) return null;
  const [identity] = await db
    .select({ uuid: players.minecraft_uuid, username: players.minecraft_username })
    .from(players)
    .where(eq(players.discord_id, discordId))
    .limit(1);
  if (!identity?.uuid) return { card, identity: null, completedTaskIds: [] as string[] };
  const completed = await db
    .select({ taskId: bingoPlayerProgress.task_id })
    .from(bingoPlayerProgress)
    .where(and(
      eq(bingoPlayerProgress.card_id, card.id),
      eq(bingoPlayerProgress.minecraft_uuid, identity.uuid),
    ));
  return {
    card,
    identity: { uuid: identity.uuid, username: identity.username },
    completedTaskIds: completed.map((row) => row.taskId),
  };
}

export async function getPendingBingoMilestones() {
  return db
    .select({
      cardId: bingoPlayerMilestones.card_id,
      minecraftUuid: bingoPlayerMilestones.minecraft_uuid,
      firstLineCompletedAt: bingoPlayerMilestones.first_line_completed_at,
      firstLineAnnouncedAt: bingoPlayerMilestones.first_line_announced_at,
      firstLineRoleAwardedAt: bingoPlayerMilestones.first_line_role_awarded_at,
      blackoutCompletedAt: bingoPlayerMilestones.blackout_completed_at,
      blackoutAnnouncedAt: bingoPlayerMilestones.blackout_announced_at,
      blackoutRoleAwardedAt: bingoPlayerMilestones.blackout_role_awarded_at,
      cardNumber: bingoCards.number,
      guildId: bingoCards.announcement_guild_id,
      channelId: bingoCards.announcement_channel_id,
      messageId: bingoCards.announcement_message_id,
      discordId: players.discord_id,
      username: players.minecraft_username,
    })
    .from(bingoPlayerMilestones)
    .innerJoin(bingoCards, eq(bingoCards.id, bingoPlayerMilestones.card_id))
    .leftJoin(players, eq(players.minecraft_uuid, bingoPlayerMilestones.minecraft_uuid))
    .where(and(
      isNotNull(bingoCards.announcement_message_id),
      or(
        and(isNotNull(bingoPlayerMilestones.first_line_completed_at), or(
          isNull(bingoPlayerMilestones.first_line_announced_at),
          isNull(bingoPlayerMilestones.first_line_role_awarded_at),
        )),
        and(isNotNull(bingoPlayerMilestones.blackout_completed_at), or(
          isNull(bingoPlayerMilestones.blackout_announced_at),
          isNull(bingoPlayerMilestones.blackout_role_awarded_at),
        )),
      ),
    ));
}

export async function markBingoMilestoneDelivered(
  cardId: number,
  minecraftUuid: string,
  kind: "line" | "blackout",
  delivery: "announcement" | "role",
): Promise<void> {
  const now = Math.floor(Date.now() / 1000);
  const field = kind === "line"
    ? delivery === "announcement" ? "first_line_announced_at" : "first_line_role_awarded_at"
    : delivery === "announcement" ? "blackout_announced_at" : "blackout_role_awarded_at";
  await db
    .update(bingoPlayerMilestones)
    .set({ [field]: now })
    .where(and(
      eq(bingoPlayerMilestones.card_id, cardId),
      eq(bingoPlayerMilestones.minecraft_uuid, minecraftUuid),
    ));
}
