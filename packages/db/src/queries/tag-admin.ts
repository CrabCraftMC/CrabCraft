import { and, asc, desc, eq, getTableColumns, ilike, isNotNull, sql } from "drizzle-orm";
import { db, type Database } from "../client";
import { cosmeticTags, playerAlts, players, playerTagAudit, playerTags, playerTagState } from "../schema";
import { grantTagInTransaction } from "./tags";

export interface TagDefinitionInput {
  id: string;
  name: string;
  description: string;
  glyph_id: string;
  sort_order: number;
  enabled: boolean;
}

export interface AdminTag extends TagDefinitionInput {
  owner_count: number;
  selected_count: number;
}

export interface TagPlayerSummary {
  minecraft_uuid: string;
  minecraft_username: string | null;
  account_type: "main" | "alt" | "unlinked";
}

export interface TagPlayerDetails {
  minecraft_uuid: string;
  minecraft_username: string | null;
  selected_tag_id: string | null;
  revision: string;
  tags: {
    id: string;
    name: string;
    glyph_id: string;
    enabled: boolean;
    granted_at: number;
    source: string;
    granted_by: string;
  }[];
  history: {
    id: string;
    tag_id: string;
    tag_name: string;
    action: "grant" | "revoke";
    ownership_changed: boolean;
    actor: string;
    source: string;
    reason: string;
    created_at: number;
  }[];
}

export class TagAdminError extends Error {
  constructor(public readonly code: "INVALID_INPUT" | "TAG_EXISTS" | "TAG_NOT_FOUND", message: string) {
    super(message);
    this.name = "TagAdminError";
  }
}

const canonicalUuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const controls = /[\u0000-\u001f\u007f-\u009f]/;
type TagTransaction = Parameters<Parameters<Database["transaction"]>[0]>[0];

async function assertCatalogueFitsProtocol(tx: TagTransaction): Promise<void> {
  const catalogue = await tx.select().from(cosmeticTags);
  if (catalogue.length > 128) {
    throw new TagAdminError("INVALID_INPUT", "The in-game picker supports a maximum of 128 tags, including disabled tags.");
  }
  const encoder = new TextEncoder();
  let longestId = 0;
  let bytes = 69;
  // TagBridgeProtocol.RESULT: fixed envelope, selected ID, then every owned tag.
  for (const tag of catalogue) {
    const idBytes = encoder.encode(tag.id).length;
    longestId = Math.max(longestId, idBytes);
    bytes += 21 + idBytes + encoder.encode(tag.name).length
      + encoder.encode(tag.description).length + encoder.encode(tag.glyph_id).length;
  }
  if (bytes + longestId > 30_000) {
    throw new TagAdminError("INVALID_INPUT", "The tag catalogue contains too much text for the in-game picker. Shorten tag names or descriptions.");
  }
}

export function validateTagDefinition(input: TagDefinitionInput): TagDefinitionInput {
  if (!input || typeof input !== "object") throw new TagAdminError("INVALID_INPUT", "A tag definition is required.");
  validateTagId(input.id);
  if (typeof input.glyph_id !== "string" || !/^[a-z0-9][a-z0-9_-]{0,127}$/.test(input.glyph_id)) {
    throw new TagAdminError("INVALID_INPUT", "Glyph IDs must use 1–128 lowercase letters, digits, underscores or hyphens.");
  }
  if (typeof input.name !== "string" || !input.name.trim() || input.name.length > 160 || controls.test(input.name)) {
    throw new TagAdminError("INVALID_INPUT", "Tag names must contain 1–160 characters without control characters.");
  }
  if (typeof input.description !== "string" || input.description.length > 1024 || controls.test(input.description)) {
    throw new TagAdminError("INVALID_INPUT", "Tag descriptions must contain at most 1,024 characters without control characters.");
  }
  if (!Number.isInteger(input.sort_order) || input.sort_order < -2_147_483_648 || input.sort_order > 2_147_483_647) {
    throw new TagAdminError("INVALID_INPUT", "Display order must be a whole number between -2147483648 and 2147483647.");
  }
  if (typeof input.enabled !== "boolean") throw new TagAdminError("INVALID_INPUT", "Enabled must be true or false.");
  return {
    id: input.id, name: input.name, description: input.description,
    glyph_id: input.glyph_id, sort_order: input.sort_order, enabled: input.enabled,
  };
}

export function validateTagId(id: string): string {
  if (typeof id !== "string" || !/^[a-z0-9][a-z0-9_-]{0,95}$/.test(id)) {
    throw new TagAdminError("INVALID_INPUT", "Tag IDs must use 1–96 lowercase letters, digits, underscores or hyphens.");
  }
  return id;
}

export function validateTagPlayerUuid(uuid: string): string {
  if (typeof uuid !== "string" || !canonicalUuid.test(uuid)) {
    throw new TagAdminError("INVALID_INPUT", "Enter a full Minecraft UUID, including hyphens.");
  }
  return uuid.toLowerCase();
}

export async function getAdminTags(database: Database = db): Promise<AdminTag[]> {
  return database.select({
    ...getTableColumns(cosmeticTags),
    owner_count: sql<number>`(SELECT count(*) FROM ${playerTags} WHERE ${playerTags.tag_id} = ${cosmeticTags.id})`.mapWith(Number),
    selected_count: sql<number>`(SELECT count(*) FROM ${playerTagState} WHERE ${playerTagState.selected_tag_id} = ${cosmeticTags.id})`.mapWith(Number),
  }).from(cosmeticTags).orderBy(asc(cosmeticTags.sort_order), asc(cosmeticTags.name), asc(cosmeticTags.id));
}

export async function createTag(input: TagDefinitionInput, database: Database = db): Promise<TagDefinitionInput> {
  const definition = validateTagDefinition(input);
  return database.transaction(async (tx) => {
    // Catalogue writers must serialise capacity checks, including concurrent creates.
    await tx.execute(sql`select pg_advisory_xact_lock(hashtext('crabcraft:tag-catalogue'))`);
    const [created] = await tx.insert(cosmeticTags).values(definition)
      .onConflictDoNothing({ target: cosmeticTags.id }).returning();
    if (!created) throw new TagAdminError("TAG_EXISTS", `A tag with ID '${definition.id}' already exists.`);
    await assertCatalogueFitsProtocol(tx);
    return created;
  });
}

export async function updateTag(input: TagDefinitionInput, database: Database = db): Promise<TagDefinitionInput> {
  const { id, ...definition } = validateTagDefinition(input);
  return database.transaction(async (tx) => {
    await tx.execute(sql`select pg_advisory_xact_lock(hashtext('crabcraft:tag-catalogue'))`);
    const [updated] = await tx.update(cosmeticTags).set(definition).where(eq(cosmeticTags.id, id)).returning();
    if (!updated) throw new TagAdminError("TAG_NOT_FOUND", `Unknown tag: ${id}`);
    await assertCatalogueFitsProtocol(tx);
    return updated;
  });
}

export async function searchTagPlayers(query: string, database: Database = db): Promise<TagPlayerSummary[]> {
  if (typeof query !== "string" || query.length > 64 || controls.test(query)) {
    throw new TagAdminError("INVALID_INPUT", "Search with a Minecraft username or full UUID (at most 64 characters).");
  }
  query = query.trim();
  if (!query) return [];
  const uuid = canonicalUuid.test(query) ? query.toLowerCase() : null;
  const pattern = `%${query.replace(/[%_\\]/g, (character) => "\\" + character)}%`;
  const [main, alts] = await Promise.all([
    database.select({
      minecraft_uuid: players.minecraft_uuid, minecraft_username: players.minecraft_username,
    }).from(players).where(and(isNotNull(players.minecraft_uuid), uuid
      ? eq(sql`lower(${players.minecraft_uuid})`, uuid) : ilike(players.minecraft_username, pattern)))
      .orderBy(asc(players.minecraft_username), asc(players.minecraft_uuid)).limit(20),
    database.select({
      minecraft_uuid: playerAlts.minecraft_uuid, minecraft_username: playerAlts.minecraft_username,
    }).from(playerAlts).where(uuid
      ? eq(sql`lower(${playerAlts.minecraft_uuid})`, uuid) : ilike(playerAlts.minecraft_username, pattern))
      .orderBy(asc(playerAlts.minecraft_username), asc(playerAlts.minecraft_uuid)).limit(20),
  ]);
  const identities = new Map<string, TagPlayerSummary>();
  for (const [rows, account_type] of [[main, "main"], [alts, "alt"]] as const) {
    for (const row of rows) {
      const minecraft_uuid = row.minecraft_uuid!.toLowerCase();
      if (!identities.has(minecraft_uuid)) identities.set(minecraft_uuid, { ...row, minecraft_uuid, account_type });
    }
  }
  if (uuid && !identities.size) return [{ minecraft_uuid: uuid, minecraft_username: null, account_type: "unlinked" }];
  return [...identities.values()].sort((left, right) =>
    (left.minecraft_username ?? "").localeCompare(right.minecraft_username ?? "") || left.minecraft_uuid.localeCompare(right.minecraft_uuid),
  ).slice(0, 20);
}

export async function getTagPlayerDetails(uuid: string, database: Database = db): Promise<TagPlayerDetails> {
  const minecraft_uuid = validateTagPlayerUuid(uuid);
  return database.transaction(async (tx) => {
    const [main] = await tx.select({ name: players.minecraft_username }).from(players)
      .where(eq(sql`lower(${players.minecraft_uuid})`, minecraft_uuid)).limit(1);
    const [alt] = main ? [] : await tx.select({ name: playerAlts.minecraft_username }).from(playerAlts)
      .where(eq(sql`lower(${playerAlts.minecraft_uuid})`, minecraft_uuid)).limit(1);
    const [state] = await tx.select().from(playerTagState).where(eq(playerTagState.minecraft_uuid, minecraft_uuid));
    const tags = await tx.select({
      id: cosmeticTags.id, name: cosmeticTags.name, glyph_id: cosmeticTags.glyph_id, enabled: cosmeticTags.enabled,
      granted_at: playerTags.granted_at, source: playerTags.source, granted_by: playerTags.granted_by,
    }).from(playerTags).innerJoin(cosmeticTags, eq(cosmeticTags.id, playerTags.tag_id))
      .where(eq(playerTags.minecraft_uuid, minecraft_uuid))
      .orderBy(asc(cosmeticTags.sort_order), asc(cosmeticTags.name), asc(cosmeticTags.id));
    const history = await tx.select({
      id: playerTagAudit.id, tag_id: playerTagAudit.tag_id, tag_name: cosmeticTags.name,
      action: sql<"grant" | "revoke">`${playerTagAudit.action}`, ownership_changed: playerTagAudit.ownership_changed,
      actor: playerTagAudit.actor, source: playerTagAudit.source, reason: playerTagAudit.reason, created_at: playerTagAudit.created_at,
    }).from(playerTagAudit).innerJoin(cosmeticTags, eq(cosmeticTags.id, playerTagAudit.tag_id))
      .where(eq(playerTagAudit.minecraft_uuid, minecraft_uuid))
      .orderBy(desc(playerTagAudit.created_at), desc(playerTagAudit.id)).limit(50);
    return {
      minecraft_uuid, minecraft_username: main?.name ?? alt?.name ?? null,
      selected_tag_id: state?.selected_tag_id ?? null, revision: (state?.revision ?? 0n).toString(), tags, history,
    };
  }, { isolationLevel: "repeatable read", accessMode: "read only" });
}

interface PlayerTagMutation {
  minecraftUuid: string;
  tagId: string;
  actor: string;
  reason: string;
}

function validateMutation(input: PlayerTagMutation): PlayerTagMutation {
  const minecraftUuid = validateTagPlayerUuid(input.minecraftUuid);
  const tagId = validateTagId(input.tagId);
  if (typeof input.actor !== "string" || !/^discord:[0-9]{1,20}$/.test(input.actor)) {
    throw new TagAdminError("INVALID_INPUT", "A verified Discord administrator is required.");
  }
  if (typeof input.reason !== "string" || !input.reason.trim() || input.reason.length > 512 || controls.test(input.reason)) {
    throw new TagAdminError("INVALID_INPUT", "Provide a reason of 1–512 characters, without control characters.");
  }
  return { minecraftUuid, tagId, actor: input.actor, reason: input.reason.trim() };
}

export async function grantPlayerTag(input: PlayerTagMutation, database: Database = db): Promise<boolean> {
  const grant = validateMutation(input);
  return database.transaction(async (tx) => {
    const [definition] = await tx.select({ id: cosmeticTags.id }).from(cosmeticTags).where(eq(cosmeticTags.id, grant.tagId));
    if (!definition) throw new TagAdminError("TAG_NOT_FOUND", `Unknown tag: ${grant.tagId}`);
    const result = await grantTagInTransaction(tx, {
      ...grant, grantedAt: Math.floor(Date.now() / 1000), source: "website-admin",
    });
    return result.ownershipChanged;
  });
}

export async function revokePlayerTag(input: PlayerTagMutation, database: Database = db): Promise<boolean> {
  const revoke = validateMutation(input);
  return database.transaction(async (tx) => {
    const now = Math.floor(Date.now() / 1000);
    await tx.insert(playerTagState).values({ minecraft_uuid: revoke.minecraftUuid, updated_at: now }).onConflictDoNothing();
    await tx.select().from(playerTagState).where(eq(playerTagState.minecraft_uuid, revoke.minecraftUuid)).for("update");
    const [definition] = await tx.select({ id: cosmeticTags.id }).from(cosmeticTags).where(eq(cosmeticTags.id, revoke.tagId));
    if (!definition) throw new TagAdminError("TAG_NOT_FOUND", `Unknown tag: ${revoke.tagId}`);
    // The ownership foreign key requires clearing the saved choice before deletion.
    await tx.update(playerTagState).set({ selected_tag_id: null }).where(and(
      eq(playerTagState.minecraft_uuid, revoke.minecraftUuid), eq(playerTagState.selected_tag_id, revoke.tagId),
    ));
    const removed = await tx.delete(playerTags).where(and(
      eq(playerTags.minecraft_uuid, revoke.minecraftUuid), eq(playerTags.tag_id, revoke.tagId),
    )).returning({ id: playerTags.tag_id });
    if (!removed.length) return false;
    await tx.insert(playerTagAudit).values({
      id: crypto.randomUUID(), minecraft_uuid: revoke.minecraftUuid, tag_id: revoke.tagId,
      action: "revoke", ownership_changed: true, actor: revoke.actor, source: "website-admin", reason: revoke.reason, created_at: now,
    });
    await tx.update(playerTagState).set({ revision: sql`${playerTagState.revision} + 1`, updated_at: now })
      .where(eq(playerTagState.minecraft_uuid, revoke.minecraftUuid));
    return true;
  });
}
