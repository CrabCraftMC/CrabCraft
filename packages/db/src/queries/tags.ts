import { and, eq, isNotNull, sql } from "drizzle-orm";
import type { Database } from "../client";
import { cosmeticTags, halloweenPlayerProgress, playerTagAudit, playerTags, playerTagState } from "../schema";

type TagTransaction = Parameters<Parameters<Database["transaction"]>[0]>[0];

export const HALLOWEEN_2026_TAG_ID = "halloween-2026-pumpkin";

export function getHalloweenTagSourceKey(eventId: string, minecraftUuid: string): string {
  return `halloween:${eventId}:${minecraftUuid.toLowerCase()}:${HALLOWEEN_2026_TAG_ID}`;
}

export interface TagGrant {
  minecraftUuid: string;
  tagId: string;
  grantedAt: number;
  actor: string;
  source: string;
  reason: string;
  sourceKey?: string;
}

/** Caller owns the transaction and must notify caches only after it commits. */
export async function grantTagInTransaction(tx: TagTransaction, grant: TagGrant) {
  const minecraftUuid = grant.minecraftUuid.toLowerCase();
  if (!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(minecraftUuid)) {
    throw new Error("A canonical Minecraft UUID is required to grant a tag");
  }
  const now = Math.floor(Date.now() / 1000);
  await tx.insert(playerTagState).values({ minecraft_uuid: minecraftUuid, updated_at: now }).onConflictDoNothing();
  const [state] = await tx.select().from(playerTagState)
    .where(eq(playerTagState.minecraft_uuid, minecraftUuid)).for("update");

  if (grant.sourceKey) {
    const [receipt] = await tx.select({ id: playerTagAudit.id }).from(playerTagAudit)
      .where(eq(playerTagAudit.source_key, grant.sourceKey));
    if (receipt) return { ownershipChanged: false, sourceAlreadyRecorded: true, revision: state.revision };
  }
  const [definition] = await tx.select({ id: cosmeticTags.id }).from(cosmeticTags)
    .where(eq(cosmeticTags.id, grant.tagId));
  if (!definition) throw new Error(`Unknown cosmetic tag: ${grant.tagId}`);

  const inserted = await tx.insert(playerTags).values({
    minecraft_uuid: minecraftUuid, tag_id: grant.tagId, granted_at: grant.grantedAt,
    source: grant.source, granted_by: grant.actor,
  }).onConflictDoNothing().returning({ id: playerTags.tag_id });
  const ownershipChanged = inserted.length > 0;
  // An automatic source is consumed even when another source already unlocked it.
  if (ownershipChanged || grant.sourceKey) {
    await tx.insert(playerTagAudit).values({
      id: crypto.randomUUID(), minecraft_uuid: minecraftUuid, tag_id: grant.tagId,
      action: "grant", ownership_changed: ownershipChanged, actor: grant.actor,
      source: grant.source, reason: grant.reason, created_at: now, source_key: grant.sourceKey ?? null,
    });
  }
  if (ownershipChanged) {
    await tx.update(playerTagState).set({ revision: sql`${playerTagState.revision} + 1`, updated_at: now })
      .where(eq(playerTagState.minecraft_uuid, minecraftUuid));
  }
  return { ownershipChanged, sourceAlreadyRecorded: false, revision: state.revision + (ownershipChanged ? 1n : 0n) };
}

/** Historical completion is authoritative; Discord role delivery is unrelated. */
export async function getHalloweenTagBackfillCandidates(database: Database, eventId: string) {
  return database.select({
    minecraftUuid: halloweenPlayerProgress.minecraft_uuid,
    completedAt: halloweenPlayerProgress.completed_at,
    alreadyAwarded: sql<boolean>`${playerTagAudit.id} IS NOT NULL`,
    alreadyOwned: sql<boolean>`${playerTags.tag_id} IS NOT NULL`,
  }).from(halloweenPlayerProgress)
    .leftJoin(playerTagAudit, eq(playerTagAudit.source_key, sql`'halloween:' || ${halloweenPlayerProgress.event_id}
      || ':' || lower(${halloweenPlayerProgress.minecraft_uuid}) || ':' || ${HALLOWEEN_2026_TAG_ID}`))
    .leftJoin(playerTags, and(
      eq(playerTags.minecraft_uuid, sql`lower(${halloweenPlayerProgress.minecraft_uuid})`),
      eq(playerTags.tag_id, HALLOWEEN_2026_TAG_ID),
    )).where(and(
    eq(halloweenPlayerProgress.event_id, eventId), isNotNull(halloweenPlayerProgress.completed_at),
    ));
}

export async function backfillHalloweenTags(database: Database, eventId: string, apply: boolean) {
  if (eventId !== "halloween-2026") throw new Error("Only --event halloween-2026 is supported");
  const candidates = await getHalloweenTagBackfillCandidates(database, eventId);
  const counts = { eligible: candidates.length, new: 0, alreadyAwarded: 0, alreadyOwned: 0 };
  for (const candidate of candidates) {
    if (!apply) {
      if (candidate.alreadyAwarded) counts.alreadyAwarded++;
      else if (candidate.alreadyOwned) counts.alreadyOwned++;
      else counts.new++;
      continue;
    }
    const result = await database.transaction((tx) => grantTagInTransaction(tx, {
      minecraftUuid: candidate.minecraftUuid, tagId: HALLOWEEN_2026_TAG_ID,
      grantedAt: candidate.completedAt!, source: `halloween:${eventId}`, actor: "system:halloween",
      reason: "Completed all three Halloween 2026 challenges",
      sourceKey: getHalloweenTagSourceKey(eventId, candidate.minecraftUuid),
    }));
    if (result.sourceAlreadyRecorded) counts.alreadyAwarded++;
    else if (result.ownershipChanged) counts.new++;
    else counts.alreadyOwned++;
  }
  return counts;
}
